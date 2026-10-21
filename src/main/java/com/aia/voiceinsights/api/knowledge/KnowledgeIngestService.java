package com.aia.voiceinsights.api.knowledge;

import com.aia.voiceinsights.api.service.ProductVectorSearchService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Turns an uploaded zip of policy documents into searchable vectors.
 *
 * <pre>
 *   product-name/            (any wrapper folders above it are ignored)
 *     Contract/  Rider/  Product Summary/  Fact Sheet/  FAQ/  Others/
 *       file.pdf | .txt | .md
 * </pre>
 *
 * The zip is read in memory (nothing is written to disk, so a hostile entry name cannot escape anywhere), with caps on entry count and
 * uncompressed size. Each document is chunked, embedded with the same Azure embedding deployment that answers searches, and swapped in
 * for whatever was stored for that document before (all or nothing), so re-uploading a corrected file never leaves stale chunks.
 * The work runs in the background, one upload at a time; the Admin page polls {@link #job}.
 */
@Service
public class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);
    private static final int MAX_ENTRIES = 2000;
    private static final long MAX_ENTRY_BYTES = 50L * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 400L * 1024 * 1024;
    private static final int EMBED_BATCH = 16;
    /** The embedding model takes 8192 tokens per input; 6000 characters stays under that even for dense text. */
    private static final int MAX_EMBED_CHARS = 6000;

    public static class FileResult {
        public String product, category, file, status, message;
        public int chunks;
        FileResult(String product, String category, String file) { this.product = product; this.category = category; this.file = file; this.status = "waiting"; }
    }

    public static class Job {
        public final String id = UUID.randomUUID().toString();
        public final String zipName;
        public final Instant startedAt = Instant.now();
        public volatile Instant finishedAt;
        public volatile String state = "running"; // running | done | failed
        public volatile String message = "Reading the zip…";
        public final List<FileResult> files = new java.util.concurrent.CopyOnWriteArrayList<>();
        public final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        public volatile int chunksStored;
        Job(String zipName) { this.zipName = zipName; }
    }

    private record Doc(String product, DocCategory category, String sourceFile, byte[] bytes, FileResult result) {}

    private final KnowledgeStore store;
    private final EmbeddingModel embeddings;
    private final ProductVectorSearchService search;
    private final int maxChars;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "knowledge-ingest"); t.setDaemon(true); return t; });
    private final Map<String, Job> jobs = java.util.Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Job> e) { return size() > 20; }
    });
    private volatile Job active;

    public KnowledgeIngestService(KnowledgeStore store, EmbeddingModel embeddings, ProductVectorSearchService search,
                                  @Value("${voice.knowledge.chunk-max-chars:1500}") int maxChars) {
        this.store = store;
        this.embeddings = embeddings;
        this.search = search;
        this.maxChars = maxChars;
    }

    public Job job(String id) { return jobs.get(id); }

    public List<Job> recent() {
        synchronized (jobs) { List<Job> l = new ArrayList<>(jobs.values()); java.util.Collections.reverse(l); return l; }
    }

    /** Starts the background job; throws IllegalStateException when another upload is still running. */
    public synchronized Job start(String zipName, byte[] zip) { return start(zipName, zip, false); }

    /**
     * With replaceAll, every stored passage is deleted and the zip is ingested from scratch. The delete only happens once the zip
     * has been read and holds at least one document, so a wrong or broken zip never empties the knowledge base.
     */
    public synchronized Job start(String zipName, byte[] zip, boolean replaceAll) {
        if (active != null && "running".equals(active.state)) throw new IllegalStateException("Another upload is still being processed. Wait for it to finish.");
        Job job = new Job(zipName == null || zipName.isBlank() ? "upload.zip" : zipName);
        active = job;
        jobs.put(job.id, job);
        worker.submit(() -> run(job, zip, replaceAll));
        return job;
    }

    private void run(Job job, byte[] zip, boolean replaceAll) {
        try {
            List<Doc> docs = unpack(job, zip);
            if (docs.isEmpty()) {
                job.state = "failed";
                job.message = "No documents found. Put the files in <Product name>/<Category>/ folders (Contract, Rider, Product Summary, Fact Sheet, FAQ, Others) as .pdf, .txt or .md. Other folders inside a product are added as Others.";
                return;
            }
            if (replaceAll) {
                job.message = "Deleting the existing embeddings…";
                int removed = store.deleteAll();
                job.warnings.add("Deleted " + removed + " existing passages before re-embedding.");
                search.refreshProductNames();
            }
            int done = 0;
            for (Doc d : docs) {
                job.message = "Embedding " + d.product() + " / " + d.category().folderName() + " / " + d.sourceFile() + " (" + (done + 1) + " of " + docs.size() + ")";
                d.result().status = "working";
                try {
                    d.result().chunks = ingest(d);
                    d.result().status = d.result().chunks == 0 ? "empty" : "done";
                    if (d.result().chunks == 0) d.result().message = "No readable text (a scanned PDF needs OCR first).";
                    job.chunksStored += d.result().chunks;
                } catch (Exception e) {
                    log.warn("Knowledge ingest failed for {}: {}", d.sourceFile(), e.toString());
                    d.result().status = "failed";
                    d.result().message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
                done++;
            }
            search.refreshProductNames();
            long failed = job.files.stream().filter(f -> "failed".equals(f.status)).count();
            job.state = failed == docs.size() ? "failed" : "done";
            job.message = failed == 0 ? "Added " + job.chunksStored + " passages from " + docs.size() + " documents. They are searchable now."
                    : (docs.size() - failed) + " documents added, " + failed + " failed — see below.";
        } catch (Exception e) {
            log.warn("Knowledge upload failed: {}", e.toString());
            job.state = "failed";
            job.message = e instanceof IOException ? "That file could not be read as a zip." : (e.getMessage() == null ? "Upload failed." : e.getMessage());
        } finally {
            job.finishedAt = Instant.now();
        }
    }

    // ── reading the zip ──
    private record Raw(String name, String[] seg, byte[] bytes) {}

    private List<Doc> unpack(Job job, byte[] zip) throws IOException {
        List<Raw> raws = new ArrayList<>();
        int entries = 0;
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                if (++entries > MAX_ENTRIES) throw new IllegalStateException("The zip has more than " + MAX_ENTRIES + " files.");
                String[] seg = e.getName().replace('\\', '/').split("/");
                boolean hidden = false;
                for (String s : seg) if (s.startsWith(".") || s.equals("__MACOSX")) hidden = true;
                if (hidden) continue; // .DS_Store, resource forks
                String leaf = seg[seg.length - 1];
                String ext = leaf.contains(".") ? leaf.substring(leaf.lastIndexOf('.') + 1).toLowerCase() : "";
                if (!List.of("pdf", "txt", "md").contains(ext)) { job.warnings.add("Skipped " + e.getName() + " — only .pdf, .txt and .md are read."); continue; }
                byte[] bytes = readCapped(in, e.getName());
                total += bytes.length;
                if (total > MAX_TOTAL_BYTES) throw new IllegalStateException("The zip is too large once unpacked.");
                raws.add(new Raw(e.getName(), seg, bytes));
            }
        }

        // A file under a category folder belongs to the product folder just above it; those folders tell us where products sit in the zip
        // (wrapper folders such as "input/" are ignored).
        java.util.Set<String> productPaths = new java.util.LinkedHashSet<>();
        for (Raw r : raws) {
            int ci = categoryIndex(r.seg());
            if (ci >= 1) productPaths.add(String.join("/", java.util.Arrays.copyOfRange(r.seg(), 0, productIndex(r.seg(), ci) + 1)));
        }

        List<Doc> docs = new ArrayList<>();
        for (Raw r : raws) {
            String[] seg = r.seg();
            String product;
            DocCategory cat;
            String rel;
            int ci = categoryIndex(r.seg());
            if (ci >= 1) {
                int pi = productIndex(seg, ci);
                product = seg[pi].trim();
                cat = DocCategory.fromFolderName(seg[ci]);
                rel = String.join("/", java.util.Arrays.copyOfRange(seg, ci + 1, seg.length));
                // Version folders between the product and the category ("2024-NB Renewal - Sep 2024") stay in the document name, so two
                // versions of the same rider don't overwrite each other, but they are never mistaken for the product.
                if (pi < ci - 1) rel = String.join("/", java.util.Arrays.copyOfRange(seg, pi + 1, ci)) + "/" + rel;
            } else {
                // Not under a category folder (a "Brochures" folder, or a file straight in the product folder): keep it, as "Others".
                String owner = null;
                for (String pp : productPaths) {
                    if (r.name().replace('\\', '/').startsWith(pp + "/") && (owner == null || pp.length() > owner.length())) owner = pp;
                }
                int depth;
                if (owner != null) depth = owner.split("/").length;
                else if (productPaths.isEmpty() && seg.length >= 2) depth = 1; // no categories anywhere: first folder is the product
                else { job.warnings.add("Skipped " + r.name() + " — could not tell which product it belongs to."); continue; }
                if (seg.length <= depth) { job.warnings.add("Skipped " + r.name() + " — not inside a product folder."); continue; }
                product = seg[depth - 1].trim();
                cat = DocCategory.OTHERS;
                rel = String.join("/", java.util.Arrays.copyOfRange(seg, depth, seg.length));
            }
            FileResult fr = new FileResult(product, cat.folderName(), rel);
            job.files.add(fr);
            docs.add(new Doc(product, cat, rel, r.bytes(), fr));
        }
        return docs;
    }

    /** A folder that names a version or period of a product rather than a product: "2024-NB Renewal - Sep 2024", "v2", "Rev 3", "2023". */
    private static final java.util.regex.Pattern VERSION_DIR = java.util.regex.Pattern.compile(
            "(?i)^(v(ersion)?\\s*[\\d.]+|rev(ision)?\\s*[\\d.]+|(19|20)\\d{2})$"
            + "|(?=.*\\b(19|20)\\d{2}\\b)(?=.*(\\bNB\\b|new business|renewal|version|revision|edition|release|update|effective|"
            + "\\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)[a-z]*\\b|\\bq[1-4]\\b)).*");

    /**
     * Index (in seg) of the product folder for a file whose category folder is at ci. Normally the folder just above the category; but
     * when that folder is a version/period folder (Product / 2024-NB Renewal - Sep 2024 / Rider / file.pdf) the product is the
     * nearest folder above it that is not one. The top folder is never skipped, and if every folder looks like a version the old
     * rule (the folder just above the category) applies.
     */
    private static int productIndex(String[] seg, int ci) {
        for (int i = ci - 1; i >= 1; i--) if (!VERSION_DIR.matcher(seg[i].trim()).matches()) return i;
        return ci - 1;
    }

    private static int categoryIndex(String[] seg) {
        for (int i = 1; i < seg.length - 1; i++) if (DocCategory.fromFolderName(seg[i]) != null) return i;
        return -1;
    }

    private static byte[] readCapped(ZipInputStream in, String name) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long n = 0;
        int r;
        while ((r = in.read(buf)) > 0) {
            n += r;
            if (n > MAX_ENTRY_BYTES) throw new IllegalStateException(name + " is larger than 50 MB.");
            out.write(buf, 0, r);
        }
        return out.toByteArray();
    }

    // ── one document ──
    private int ingest(Doc d) throws Exception {
        String text;
        int pages = 1;
        if (d.sourceFile().toLowerCase().endsWith(".pdf")) {
            try (PDDocument pdf = PDDocument.load(d.bytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                text = stripper.getText(pdf);
                pages = pdf.getNumberOfPages();
            }
        } else {
            text = new String(d.bytes(), StandardCharsets.UTF_8);
        }
        List<DocumentChunker.Section> sections = new DocumentChunker(maxChars).chunk(text, d.category());
        if (sections.isEmpty()) {
            store.deleteDocument(d.product(), d.category().folderName(), d.sourceFile());
            return 0;
        }
        List<KnowledgeStore.Chunk> chunks = new ArrayList<>();
        for (int i = 0; i < sections.size(); i++) {
            var s = sections.get(i);
            String embedText = "Product: " + d.product() + " | Category: " + d.category().folderName() + " | Section: " + s.title() + "\n\n" + s.content();
            if (embedText.length() > MAX_EMBED_CHARS) embedText = embedText.substring(0, MAX_EMBED_CHARS);
            chunks.add(new KnowledgeStore.Chunk(chunkId(d, i), d.product(), d.category().folderName(), d.sourceFile(), i, sections.size(),
                    s.title(), pages, s.content(), embedText));
        }
        List<float[]> vectors = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH) {
            List<String> batch = chunks.subList(i, Math.min(i + EMBED_BATCH, chunks.size())).stream().map(KnowledgeStore.Chunk::embeddingText).toList();
            vectors.addAll(embedWithRetry(batch));
        }
        store.replaceDocument(d.product(), d.category().folderName(), d.sourceFile(), chunks, vectors);
        return chunks.size();
    }

    private List<float[]> embedWithRetry(List<String> texts) throws InterruptedException {
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                List<float[]> v = embeddings.embed(texts);
                if (v.size() != texts.size()) throw new IllegalStateException("The embedding service returned the wrong number of vectors.");
                return v;
            } catch (RuntimeException e) {
                last = e;
                Thread.sleep(1500L * (attempt + 1));
            }
        }
        throw new IllegalStateException("Embedding failed: " + last.getMessage());
    }

    // Same id scheme as voice-insights-ingestor, so re-ingesting a file it already loaded replaces rather than duplicates.
    private static String chunkId(Doc d, int index) {
        return slug(d.product()) + "::" + d.category().name().toLowerCase() + "::" + slug(d.sourceFile()) + "::" + index;
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }
}
