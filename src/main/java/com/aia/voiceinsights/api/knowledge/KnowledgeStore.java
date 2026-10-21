package com.aia.voiceinsights.api.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The policy-document vectors: {@code voice_insights.product_chunks}, the table the Need agent, Juno and the RAG validator search
 * (see ProductVectorSearchService). Same columns voice-insights-ingestor writes, so both can feed it.
 */
@Component
public class KnowledgeStore {

    public record Chunk(String chunkId, String productName, String docCategory, String sourceFile, int chunkIndex, int totalChunks,
                        String sectionTitle, int pageCount, String content, String embeddingText) {}

    public record FileRow(String category, String sourceFile, int chunks) {}

    public record ProductRow(String productName, int files, int chunks, String updatedAt, List<FileRow> documents) {}

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final String schema;
    private final ObjectMapper mapper = new ObjectMapper();

    public KnowledgeStore(JdbcTemplate jdbc, TransactionTemplate tx, @Value("${voice.db.schema:voice_insights}") String schema) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.schema = schema;
    }

    /** Fresh installs have no table yet (voice-insights-ingestor used to be the one to create it). */
    @PostConstruct
    void ensureTable() {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
            jdbc.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS %s.product_chunks (
                        chunk_id TEXT PRIMARY KEY, product_name TEXT NOT NULL, doc_category TEXT NOT NULL, source_file TEXT NOT NULL,
                        chunk_index INTEGER, total_chunks INTEGER, section_title TEXT, page_start INTEGER, page_end INTEGER,
                        content TEXT, embedding_text TEXT, payload JSONB, embedding vector(3072), created_at TIMESTAMPTZ DEFAULT NOW())
                    """.formatted(schema));
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vi_chunks_product ON %s.product_chunks (product_name)".formatted(schema));
            jdbc.execute("CREATE INDEX IF NOT EXISTS idx_vi_chunks_category ON %s.product_chunks (doc_category)".formatted(schema));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(KnowledgeStore.class).warn("Could not prepare product_chunks: {}", e.getMessage());
        }
    }

    /** Replaces everything stored for one document with these chunks (all or nothing). */
    public void replaceDocument(String product, String category, String sourceFile, List<Chunk> rawChunks, List<float[]> vectors) {
        // Last line of defence: Postgres rejects NUL (0x00) in text and jsonb, whatever the source file contained.
        List<Chunk> chunks = rawChunks.stream().map(c -> new Chunk(DocumentChunker.clean(c.chunkId()), DocumentChunker.clean(c.productName()),
                DocumentChunker.clean(c.docCategory()), DocumentChunker.clean(c.sourceFile()), c.chunkIndex(), c.totalChunks(),
                DocumentChunker.clean(c.sectionTitle()), c.pageCount(), DocumentChunker.clean(c.content()), DocumentChunker.clean(c.embeddingText()))).toList();
        tx.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM %s.product_chunks WHERE product_name = ? AND doc_category = ? AND source_file = ?".formatted(schema),
                    product, category, sourceFile);
            for (int i = 0; i < chunks.size(); i++) {
                Chunk c = chunks.get(i);
                jdbc.update("""
                        INSERT INTO %s.product_chunks (chunk_id, product_name, doc_category, source_file, chunk_index, total_chunks,
                            section_title, page_start, page_end, content, embedding_text, payload, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?::jsonb, ?::vector)
                        ON CONFLICT (chunk_id) DO UPDATE SET section_title = EXCLUDED.section_title, content = EXCLUDED.content,
                            embedding_text = EXCLUDED.embedding_text, payload = EXCLUDED.payload, embedding = EXCLUDED.embedding
                        """.formatted(schema),
                        c.chunkId(), c.productName(), c.docCategory(), c.sourceFile(), c.chunkIndex(), c.totalChunks(),
                        c.sectionTitle(), c.pageCount(), c.content(), c.embeddingText(), payload(c), vectorLiteral(vectors.get(i)));
            }
        });
    }

    /** Every product with its documents, newest upload first. */
    public List<ProductRow> products() {
        Map<String, List<FileRow>> docs = new LinkedHashMap<>();
        jdbc.query("""
                SELECT product_name, doc_category, source_file, count(*) AS n
                FROM %s.product_chunks GROUP BY 1, 2, 3 ORDER BY 1, 2, 3
                """.formatted(schema), rs -> {
            docs.computeIfAbsent(rs.getString(1), k -> new java.util.ArrayList<>())
                    .add(new FileRow(rs.getString(2), rs.getString(3), rs.getInt(4)));
        });
        Map<String, String> updated = new LinkedHashMap<>();
        jdbc.query("SELECT product_name, max(created_at)::text FROM %s.product_chunks GROUP BY 1".formatted(schema),
                rs -> { updated.put(rs.getString(1), rs.getString(2)); });
        return docs.entrySet().stream()
                .map(e -> new ProductRow(e.getKey(), e.getValue().size(), e.getValue().stream().mapToInt(FileRow::chunks).sum(),
                        updated.get(e.getKey()), e.getValue()))
                .sorted((a, b) -> String.valueOf(b.updatedAt()).compareTo(String.valueOf(a.updatedAt())))
                .toList();
    }

    /** Removes every embedded passage (the Admin "start from scratch" reset). */
    public int deleteAll() {
        return jdbc.update("DELETE FROM %s.product_chunks".formatted(schema));
    }

    public int deleteProduct(String product) {
        return jdbc.update("DELETE FROM %s.product_chunks WHERE product_name = ?".formatted(schema), product);
    }

    public int deleteDocument(String product, String category, String sourceFile) {
        return jdbc.update("DELETE FROM %s.product_chunks WHERE product_name = ? AND doc_category = ? AND source_file = ?".formatted(schema),
                product, category, sourceFile);
    }

    private String payload(Chunk c) {
        try {
            return mapper.writeValueAsString(Map.of("chunkId", c.chunkId(), "productName", c.productName(), "docCategory", c.docCategory(),
                    "sourceFile", c.sourceFile(), "chunkIndex", c.chunkIndex(), "totalChunks", c.totalChunks(),
                    "sectionTitle", c.sectionTitle(), "content", c.content(), "embeddingText", c.embeddingText()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String vectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) { if (i > 0) sb.append(','); sb.append(v[i]); }
        return sb.append(']').toString();
    }
}
