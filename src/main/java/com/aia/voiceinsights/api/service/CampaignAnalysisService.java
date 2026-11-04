package com.aia.voiceinsights.api.service;

import com.aia.voiceinsights.api.model.Campaign;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.azure.openai.AzureOpenAiChatOptions;
import org.springframework.ai.azure.openai.AzureOpenAiResponseFormat;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.Media;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads a campaign poster ONCE, when it is uploaded, so that nothing about the poster is analysed while a customer is being advised.
 *
 * <p>The reading is done by a vision model looking at the picture itself (not OCR followed by text processing): it handles stylised type, text
 * on gradients and icons, which classic OCR does not, and it returns meaning as well as words. It returns the poster's text verbatim (kept for
 * the admin to check against), a summary, themes, benefits, the audience and dates it can see, and a set of short "customer signals": the sorts of
 * thing a customer or advisor might say that this campaign answers. Those signals, the overview and each benefit are embedded, and the matching at
 * debrief time compares the customer's words with them by meaning (see {@link CampaignAgentService}).
 *
 * <p>Nothing here publishes anything. The result is a DRAFT for an admin to review and edit; a campaign only reaches customers when an admin
 * activates it.
 */
@Service
public class CampaignAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CampaignAnalysisService.class);

    static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    static final int MIN_SIDE = 300;
    static final int MAIN_SIDE = 1600;
    static final int THUMB_SIDE = 480;

    private static final String SYSTEM = """
            You read a marketing poster for an insurance company's advisors. Look at the whole image carefully and report ONLY what it shows.
            Never invent an offer, a number, a date, a partner or an eligibility rule that is not on the poster. If something is not legible or not
            there, leave it null or empty, and say so in "confidence".

            A poster usually promotes ONE campaign. Some promote two or more, each with its own brand or programme name, its own headline and its own
            offer, usually in separate panels (for example the top half and the bottom half, or the left and the right). Report one entry in
            "campaigns" for each such campaign, up to 4. Do NOT split one campaign into several because it lists several benefits, and do not make
            an entry for a footer, a logo strip or small print. Each entry covers only what belongs to that campaign: its own summary, benefits,
            text and signals. Dates, age limits and small print that apply to the whole poster are repeated in every entry.

            Respond with ONLY a JSON object, no markdown fences:
            {
              "isCampaignPoster": true or false,
              "campaigns": [ {
                "name": "the programme or brand this campaign promotes, as written (for example 'AIA Vitality'), not a small tag line or category label such as 'Healthy Living Campaign'; if there is no brand, its main headline; max 6 words",
                "summary": "what the campaign is and who it is for, in two plain sentences, max 45 words",
                "themes": ["3 to 6 short topics, 1-3 words each: e.g. fitness, health screening, family protection, rewards"],
                "benefits": ["each benefit or offer exactly as the poster states it, max 15 words each, up to 6"],
                "customerSignals": ["6 to 8 short statements about the CUSTOMER'S own interest, goal or worry that this campaign answers, each 4-12 words, in plain English, covering different ways of saying it: e.g. 'wants to stay fit and exercise regularly', 'interested in gym and wellness', 'worried about what he leaves his children'. Always what the customer wants or feels; never what the advisor or the poster offers or suggests (not 'suggests estate planning' but 'wants to plan his estate')"],
                "minAge": integer or null, "maxAge": integer or null, "audienceNote": "who may join, as stated, or null",
                "startsOn": "YYYY-MM-DD or null", "endsOn": "YYYY-MM-DD or null",
                "language": "the language the poster is written in, as a code: en, zh, ms, ta, th, fil, vi",
                "callToAction": "what the poster asks the reader to do, or null",
                "terms": "the small print or conditions as written, or null",
                "verbatimText": "every piece of text of this campaign, in reading order, one line per block",
                "confidence": "high | medium | low: how sure you are that the fields above match the poster",
                "region": {"x": 0.0, "y": 0.0, "w": 1.0, "h": 1.0}
              } ]
            }
            "region" is where this campaign sits in the image, as fractions of the image's width and height (x and y: the top-left corner; w and h: the
            size). A single campaign is the whole image {"x":0,"y":0,"w":1,"h":1}. Regions of different campaigns must not overlap.
            Dates: read "1 Nov 2026 - 31 Jan 2027" as startsOn 2026-11-01 and endsOn 2027-01-31. If only an end date is given, startsOn is null.
            customerSignals describe what the CUSTOMER wants or is interested in (never a product pitch). Use "suggest", never "recommend".
            """;

    private final CampaignStore store;
    private final ChatModel chatModel;
    private final EmbeddingModel embeddings;
    private final ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "campaign-analysis");
        t.setDaemon(true);
        return t;
    });

    public CampaignAnalysisService(CampaignStore store, @Qualifier("localizingChatModel") ChatModel chatModel, EmbeddingModel embeddings) {
        this.store = store;
        this.chatModel = chatModel;
        this.embeddings = embeddings;
    }

    /**
     * The vision calls run on their own pool, so a call that never answers cannot hold up the workers that do the rest. Azure now and then leaves a
     * request open for a minute or more; a reading therefore has a deadline per attempt and one retry (a poster is read once, so a retry is cheap).
     */
    private final ExecutorService vision = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "campaign-vision");
        t.setDaemon(true);
        return t;
    });
    static final int ATTEMPTS = 2;
    static final long ATTEMPT_TIMEOUT_MS = 45_000;

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        vision.shutdownNow();
    }

    /** After a restart, a poster that was being read when the service stopped would stay "Reading…" for ever: read it again. */
    @PostConstruct
    void resumeUnfinished() {
        try {
            for (String id : store.staleAnalyzing(2)) {
                log.info("Campaign {} was left unfinished; reading its poster again", id);
                reanalyse(id);
            }
        } catch (RuntimeException e) {
            log.warn("Could not resume unfinished campaign readings: {}", e.toString());
        }
    }

    /** Thrown for a file the admin should fix (not an image, too small, too large); the message is shown to them. */
    public static class BadPoster extends RuntimeException {
        public BadPoster(String message) { super(message); }
    }

    /** Accepts an uploaded poster, stores it and starts reading it in the background. Returns the new campaign's id. */
    public String submit(byte[] bytes, String filename) {
        if (bytes == null || bytes.length == 0) throw new BadPoster("The file is empty.");
        if (bytes.length > MAX_IMAGE_BYTES) throw new BadPoster("The image is larger than " + MAX_IMAGE_BYTES / 1024 / 1024 + " MB. Please export a smaller one.");
        BufferedImage src = decode(bytes);
        if (Math.min(src.getWidth(), src.getHeight()) < MIN_SIDE) throw new BadPoster("The image is too small to read (at least " + MIN_SIDE + " pixels on each side).");
        byte[] main = jpeg(scaled(src, MAIN_SIDE), 0.9f);
        byte[] thumb = jpeg(scaled(src, THUMB_SIDE), 0.82f);
        String id = UUID.randomUUID().toString();
        store.create(id, nameFrom(filename), "image/jpeg", main, thumb);
        executor.submit(() -> analyse(id, main));
        return id;
    }

    /** Reads the stored poster again (after a failure, or when the admin asks). */
    public void reanalyse(String id) {
        var image = store.image(id, false).orElseThrow(() -> new BadPoster("This campaign has no poster image."));
        store.markAnalyzing(id);
        executor.submit(() -> analyse(id, image.data()));
    }

    /** One campaign found on a poster, and where on the poster it sits (x, y, w, h as fractions; null when the model gave none that can be used). */
    record Panel(Campaign campaign, double[] region) {}

    static final int MAX_PANELS = 4;

    void analyse(String id, byte[] image) {
        try {
            List<Panel> panels = extract(image);
            if (panels.size() == 1) {
                Campaign extracted = panels.get(0).campaign();
                List<CampaignStore.Vec> vectors = embed(id, extracted);
                store.saveAnalysis(id, extracted, vectors);
                log.info("Campaign {} read: \"{}\", {} themes, {} embeddings, confidence {}", id, extracted.name(), extracted.themes().size(), vectors.size(), extracted.confidence());
            } else {
                splitPoster(id, image, panels);
            }
        } catch (Exception e) {
            log.warn("Campaign {}: the poster could not be read: {}", id, e.toString());
            store.markFailed(id, e instanceof BadPoster ? e.getMessage() : "The poster could not be read just now. Try again.");
        }
    }

    /**
     * A poster that holds several campaigns becomes one campaign each, every one with its own part of the poster as its picture, so the card on the
     * summary page and the viewer show that campaign and not its neighbour. Everything (crops, embeddings) is prepared before anything is written,
     * the extra campaigns are written first and the uploaded one last: an admin watching the list sees the poster finish only when all of them exist.
     */
    private void splitPoster(String id, byte[] image, List<Panel> found) {
        List<Panel> panels = found;
        BufferedImage src = decode(image);
        boolean crop = canCrop(panels);
        if (crop) panels = refine(src, panels);
        List<byte[]> mains = new ArrayList<>(), thumbs = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        List<List<CampaignStore.Vec>> vectors = new ArrayList<>();
        for (int i = 0; i < panels.size(); i++) {
            String cid = i == 0 ? id : UUID.randomUUID().toString();
            BufferedImage part = crop ? crop(src, panels.get(i).region()) : src;
            ids.add(cid);
            mains.add(crop ? jpeg(scaled(part, MAIN_SIDE), 0.9f) : image);
            thumbs.add(jpeg(scaled(part, THUMB_SIDE), 0.82f));
            vectors.add(embed(cid, panels.get(i).campaign()));
        }
        for (int i = panels.size() - 1; i >= 0; i--) {
            Campaign c = panels.get(i).campaign();
            if (i == 0) {
                store.replaceImage(id, mains.get(0), thumbs.get(0));
                store.saveAnalysis(id, c, vectors.get(0));
            } else {
                store.createAnalysed(ids.get(i), c, vectors.get(i), "image/jpeg", mains.get(i), thumbs.get(i));
            }
        }
        log.info("Poster {} held {} campaigns ({}): {}", id, panels.size(), crop ? "each with its own part of the picture" : "all keep the whole picture",
                panels.stream().map(p -> p.campaign().name()).toList());
    }

    private List<Panel> extract(byte[] image) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            Future<List<Panel>> call = vision.submit(() -> read(image));
            try {
                return call.get(ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                call.cancel(true);
                last = e;
                log.warn("Poster reading attempt {} gave no answer in {} s", attempt, ATTEMPT_TIMEOUT_MS / 1000);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof BadPoster bad) throw bad; // the poster itself is the problem: a retry cannot help
                last = e;
                log.warn("Poster reading attempt {} failed: {}", attempt, e.getCause() == null ? e : e.getCause().toString());
            }
        }
        throw new IllegalStateException("The poster could not be read in time.", last);
    }

    private List<Panel> read(byte[] image) throws Exception {
        var response = chatModel.call(new Prompt(
                List.of(new SystemMessage(SYSTEM),
                        new UserMessage("Read this poster.", new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(image)))),
                AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.1).maxTokens(4500).build()));
        return parseAll(response.getResult().getOutput().getText());
    }

    /** The first campaign on a poster; for a poster with one campaign, the campaign. Package-private for tests. */
    Campaign parse(String json) throws Exception { return parseAll(json).get(0).campaign(); }

    /**
     * Turns the model's JSON into campaign drafts (one per campaign on the poster, at most {@value #MAX_PANELS}), bounding every field. An entry
     * that cannot be understood is left out when another can; a poster none of whose entries can be understood is refused. A reply without a
     * "campaigns" list is read as a single campaign, so an older-shaped answer still works. Package-private for tests.
     */
    List<Panel> parseAll(String json) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (root.has("isCampaignPoster") && !root.path("isCampaignPoster").asBoolean(true)) throw new BadPoster("This image does not look like a campaign poster.");
        List<JsonNode> nodes = new ArrayList<>();
        if (root.path("campaigns").isArray() && root.path("campaigns").size() > 0) root.path("campaigns").forEach(nodes::add);
        else nodes.add(root);
        List<Panel> panels = new ArrayList<>();
        BadPoster firstProblem = null;
        for (JsonNode n : nodes) {
            if (panels.size() == MAX_PANELS) break;
            try {
                Campaign c = campaign(n);
                if (panels.stream().noneMatch(p -> p.campaign().name().equalsIgnoreCase(c.name()))) panels.add(new Panel(c, region(n.path("region"))));
            } catch (BadPoster e) {
                if (firstProblem == null) firstProblem = e;
            }
        }
        if (panels.isEmpty()) throw firstProblem != null ? firstProblem : new BadPoster("The poster could not be understood. Is the text readable?");
        return List.copyOf(panels);
    }

    private static Campaign campaign(JsonNode n) {
        String name = text(n, "name", 80);
        String summary = text(n, "summary", 400);
        if (name == null || summary == null) throw new BadPoster("The poster could not be understood. Is the text readable?");
        List<String> signals = strings(n.path("customerSignals"), 8, 120);
        if (signals.isEmpty()) throw new BadPoster("The poster could not be understood. Is the text readable?");
        return new Campaign(null, name, Campaign.DRAFT, Campaign.READY, null, Wording.suggest(summary),
                strings(n.path("themes"), 6, 40), strings(n.path("benefits"), 6, 160).stream().map(Wording::suggest).toList(), signals,
                age(n, "minAge"), age(n, "maxAge"), text(n, "audienceNote", 200), date(n, "startsOn"), date(n, "endsOn"), List.of(),
                language(n.path("language").asText("")), text(n, "callToAction", 120), text(n, "terms", 600), text(n, "verbatimText", 4000),
                List.of("high", "medium", "low").contains(n.path("confidence").asText("")) ? n.path("confidence").asText() : "medium", true, null, null);
    }

    /** {x, y, w, h} as fractions of the picture, or null when it is missing or not a believable part of it. */
    static double[] region(JsonNode r) {
        if (!r.isObject()) return null;
        for (String k : List.of("x", "y", "w", "h")) if (!r.path(k).isNumber()) return null;
        double x = r.path("x").asDouble(), y = r.path("y").asDouble(), w = r.path("w").asDouble(), h = r.path("h").asDouble();
        if (x < -0.02 || y < -0.02 || w < 0.1 || h < 0.1 || x + w > 1.03 || y + h > 1.03) return null;
        x = Math.max(0, x);
        y = Math.max(0, y);
        return new double[] {x, y, Math.min(w, 1 - x), Math.min(h, 1 - y)};
    }

    /** Whether every campaign has a region of its own, so each can be given its own part of the picture. Overlapping regions mean the model was guessing. */
    static boolean canCrop(List<Panel> panels) {
        for (Panel p : panels) if (p.region() == null) return false;
        for (int i = 0; i < panels.size(); i++) {
            for (int j = i + 1; j < panels.size(); j++) {
                double[] a = panels.get(i).region(), b = panels.get(j).region();
                double w = Math.min(a[0] + a[2], b[0] + b[2]) - Math.max(a[0], b[0]);
                double h = Math.min(a[1] + a[3], b[1] + b[3]) - Math.max(a[1], b[1]);
                if (w > 0 && h > 0 && w * h > 0.15 * Math.min(a[2] * a[3], b[2] * b[3])) return false;
            }
        }
        return true;
    }

    private static final String LOCATE = """
            The picture is a poster that holds several campaigns, in separate panels. A ruler has been drawn on it: coloured lines, each labelled with a
            percentage of the picture's %AXIS% (0% at the %START%, 100% at the %END%). Using the ruler, say where each panel ends and the next begins.
            The panels, in order from the %START% to the %END%: %NAMES%.
            Respond with ONLY JSON: {"cuts": [..]}, %N% number(s) between 0 and 1 in ascending order (a label of 30% is 0.3): the first is where panel 1
            ends and panel 2 begins, and so on. Place each cut exactly on the visible edge between the two panels' backgrounds.
            """;

    /**
     * The first reading gives panel positions only roughly (it says "halfway"). For panels stacked top to bottom, or side by side, a second look at the
     * picture with a percentage ruler drawn on it places each cut far more accurately; the cut is then snapped to the nearest real edge in the pixels.
     * Anything that goes wrong here leaves the first answer, snapped on its own, so a poster is never refused because of this step.
     */
    List<Panel> refine(BufferedImage src, List<Panel> panels) {
        boolean stacked = panels.stream().allMatch(p -> p.region()[2] > 0.85), sideBySide = panels.stream().allMatch(p -> p.region()[3] > 0.85);
        if (stacked == sideBySide) return panels;
        int axis = stacked ? 1 : 0;
        List<Panel> sorted = new ArrayList<>(panels);
        sorted.sort(java.util.Comparator.comparingDouble(p -> p.region()[axis]));
        try {
            byte[] ruled = jpeg(withRuler(src, stacked), 0.88f);
            String prompt = LOCATE.replace("%AXIS%", stacked ? "height" : "width").replace("%START%", stacked ? "top" : "left").replace("%END%", stacked ? "bottom" : "right")
                    .replace("%NAMES%", String.join("; ", sorted.stream().map(p -> p.campaign().name()).toList())).replace("%N%", String.valueOf(sorted.size() - 1));
            Future<String> call = vision.submit(() -> chatModel.call(new Prompt(
                    List.of(new SystemMessage(prompt), new UserMessage("Where are the cuts?", new Media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(ruled)))),
                    AzureOpenAiChatOptions.builder().responseFormat(AzureOpenAiResponseFormat.JSON).temperature(0.0).maxTokens(200).build())).getResult().getOutput().getText());
            JsonNode cuts = mapper.readTree(call.get(ATTEMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).path("cuts");
            if (cuts.isArray() && cuts.size() == sorted.size() - 1) {
                double[] edges = new double[sorted.size() + 1];
                edges[sorted.size()] = 1;
                for (int i = 0; i < cuts.size(); i++) {
                    edges[i + 1] = cuts.get(i).asDouble(-1);
                    if (edges[i + 1] <= edges[i] + 0.05 || edges[i + 1] >= 0.95) throw new IllegalStateException("cuts not usable: " + cuts);
                }
                List<Panel> placed = new ArrayList<>();
                for (Panel p : panels) {
                    int i = sorted.indexOf(p);
                    double[] r = p.region().clone();
                    r[axis] = edges[i];
                    r[axis + 2] = edges[i + 1] - edges[i];
                    r[1 - axis] = 0;
                    r[3 - axis] = 1;
                    placed.add(new Panel(p.campaign(), r));
                }
                log.info("Poster panels placed by the ruler pass at {}", cuts);
                return snapToSeams(src, placed, 0.05);
            }
        } catch (Exception e) {
            log.warn("The second look at where the panels meet did not work ({}); using the first reading", e.toString());
        }
        return snapToSeams(src, panels);
    }

    /** A copy of the picture with a labelled line every 10% along the axis the panels are ordered by. */
    static BufferedImage withRuler(BufferedImage src, boolean rows) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        int len = rows ? src.getHeight() : src.getWidth(), across = rows ? src.getWidth() : src.getHeight();
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, Math.max(14, len / 55)));
        for (int pct = 10; pct <= 90; pct += 10) {
            int at = len * pct / 100;
            g.setColor(new Color(0, 230, 255));
            g.setStroke(new java.awt.BasicStroke(Math.max(2f, len / 700f)));
            if (rows) g.drawLine(0, at, across, at); else g.drawLine(at, 0, at, across);
            String label = pct + "%";
            int w = g.getFontMetrics().stringWidth(label) + 8, h = g.getFontMetrics().getHeight();
            for (int k = 0; k < 2; k++) {
                int x = rows ? (k == 0 ? 2 : across - w - 2) : at + 3, y = rows ? at - h : (k == 0 ? 2 : across - h - 2);
                g.setColor(Color.BLACK);
                g.fillRect(x, y, w, h);
                g.setColor(new Color(0, 230, 255));
                g.drawString(label, x + 4, y + g.getFontMetrics().getAscent());
            }
        }
        g.dispose();
        return out;
    }

    /**
     * A vision model places the edge between two panels only roughly (it tends to say "halfway"). When the panels are stacked or side by side, the real edge
     * is where the picture changes most abruptly, so each boundary is moved to the row (or column) of strongest change within 20% of where the model put it.
     */
    static List<Panel> snapToSeams(BufferedImage src, List<Panel> panels) { return snapToSeams(src, panels, 0.2); }

    static List<Panel> snapToSeams(BufferedImage src, List<Panel> panels, double window) {
        boolean stacked = panels.stream().allMatch(p -> p.region()[2] > 0.85), sideBySide = panels.stream().allMatch(p -> p.region()[3] > 0.85);
        if (panels.size() < 2 || stacked == sideBySide) return panels;
        int axis = stacked ? 1 : 0; // the coordinate the panels are ordered by
        List<Panel> sorted = new ArrayList<>(panels);
        sorted.sort(java.util.Comparator.comparingDouble(p -> p.region()[axis]));
        double[] start = new double[sorted.size()], end = new double[sorted.size()];
        for (int i = 0; i < sorted.size(); i++) {
            start[i] = sorted.get(i).region()[axis];
            end[i] = start[i] + sorted.get(i).region()[axis + 2];
        }
        for (int i = 0; i + 1 < sorted.size(); i++) {
            double seam = seam(src, (end[i] + start[i + 1]) / 2, stacked, window);
            end[i] = seam;
            start[i + 1] = seam;
        }
        List<Panel> out = new ArrayList<>();
        for (Panel p : panels) {
            int i = sorted.indexOf(p);
            double[] r = p.region().clone();
            r[axis] = start[i];
            r[axis + 2] = Math.max(0.05, end[i] - start[i]);
            out.add(new Panel(p.campaign(), r));
        }
        return out;
    }

    /** The row (stacked) or column (side by side) near {@code guess}, as a fraction, where neighbouring lines of the picture differ most. */
    static double seam(BufferedImage src, double guess, boolean rows, double window) {
        int length = rows ? src.getHeight() : src.getWidth(), across = rows ? src.getWidth() : src.getHeight();
        int lo = Math.max(1, (int) ((guess - window) * length)), hi = Math.min(length - 1, (int) ((guess + window) * length));
        int step = Math.max(1, across / 200);
        double best = -1;
        int bestAt = (int) (guess * length);
        for (int k = lo; k <= hi; k++) {
            double diff = 0;
            int n = 0;
            for (int j = 0; j < across; j += step) {
                int a = rows ? src.getRGB(j, k) : src.getRGB(k, j), b = rows ? src.getRGB(j, k - 1) : src.getRGB(k - 1, j);
                diff += Math.abs(((a >> 16 & 255) + (a >> 8 & 255) + (a & 255)) - ((b >> 16 & 255) + (b >> 8 & 255) + (b & 255)));
                n++;
            }
            if (diff / n > best) { best = diff / n; bestAt = k; }
        }
        return (double) bestAt / length;
    }

    /** The part of a picture a region names, with a little margin so a heading on the edge is not cut. */
    static BufferedImage crop(BufferedImage src, double[] r) {
        int pad = (int) Math.round(0.004 * Math.max(src.getWidth(), src.getHeight()));
        int x = Math.max(0, (int) Math.round(r[0] * src.getWidth()) - pad), y = Math.max(0, (int) Math.round(r[1] * src.getHeight()) - pad);
        int x2 = Math.min(src.getWidth(), (int) Math.round((r[0] + r[2]) * src.getWidth()) + pad);
        int y2 = Math.min(src.getHeight(), (int) Math.round((r[1] + r[3]) * src.getHeight()) + pad);
        return src.getSubimage(x, y, Math.max(1, x2 - x), Math.max(1, y2 - y));
    }

    /** The embeddings of a campaign: its overview, each benefit and each customer signal. */
    List<CampaignStore.Vec> embed(String id, Campaign c) {
        List<String> kinds = new ArrayList<>(), labels = new ArrayList<>();
        kinds.add("overview");
        labels.add(c.name() + ". " + c.summary() + " Topics: " + String.join(", ", c.themes()) + ".");
        for (String b : c.benefits()) { kinds.add("benefit"); labels.add(b); }
        for (String s : c.customerSignals()) { kinds.add("signal"); labels.add(s); }
        List<float[]> vectors = embeddings.embed(labels);
        List<CampaignStore.Vec> out = new ArrayList<>();
        for (int i = 0; i < labels.size(); i++) out.add(new CampaignStore.Vec(id, kinds.get(i), labels.get(i), vectors.get(i)));
        return out;
    }

    /** The embeddings for an edited campaign: same as above, used when the admin changes what the campaign is about. */
    public List<CampaignStore.Vec> reembed(String id, Campaign c) { return embed(id, c); }

    // ── images ───────────────────────────────────────────────────────────────────────────────────────────────

    static BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) throw new BadPoster("This file is not an image Spark can read. Please upload a PNG or JPG.");
            return img;
        } catch (java.io.IOException e) {
            throw new BadPoster("This file is not an image Spark can read. Please upload a PNG or JPG.");
        }
    }

    /** Scaled so the longer side is at most {@code max}, on a white background (a transparent PNG becomes a normal picture). */
    static BufferedImage scaled(BufferedImage src, int max) {
        double k = Math.min(1.0, (double) max / Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * k)), h = Math.max(1, (int) Math.round(src.getHeight() * k));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    static byte[] jpeg(BufferedImage img, float quality) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(img, null, null), p);
            } finally {
                writer.dispose();
            }
            return bos.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String nameFrom(String filename) {
        String base = filename == null ? "" : filename.replaceAll("^.*[\\\\/]", "").replaceAll("\\.[A-Za-z0-9]+$", "").replaceAll("[-_]+", " ").strip();
        return base.isEmpty() ? "New campaign" : base.substring(0, Math.min(80, base.length()));
    }

    // ── field helpers ────────────────────────────────────────────────────────────────────────────────────────

    private static String text(JsonNode n, String field, int max) {
        String v = n.path(field).isNull() ? "" : n.path(field).asText("").strip();
        return v.isEmpty() || "null".equalsIgnoreCase(v) ? null : v.substring(0, Math.min(max, v.length()));
    }

    private static List<String> strings(JsonNode arr, int maxItems, int maxLen) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (arr.isArray()) for (JsonNode x : arr) {
            String v = x.asText("").strip();
            if (!v.isEmpty()) out.add(v.substring(0, Math.min(maxLen, v.length())));
            if (out.size() == maxItems) break;
        }
        return List.copyOf(out);
    }

    private static Integer age(JsonNode n, String f) {
        if (!n.path(f).isNumber()) return null;
        int a = n.path(f).asInt();
        return a >= 0 && a <= 120 ? a : null;
    }

    private static String date(JsonNode n, String f) {
        String v = text(n, f, 10);
        if (v == null) return null;
        try { return LocalDate.parse(v).toString(); } catch (DateTimeParseException e) { return null; }
    }

    private static List<String> language(String code) {
        String c = code.toLowerCase();
        return Languages.isSupported(c) ? List.of(c) : List.of();
    }
}
