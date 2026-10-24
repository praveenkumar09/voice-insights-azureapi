package com.aia.voiceinsights.api.controller;

import com.aia.voiceinsights.api.knowledge.KnowledgeIngestService;
import com.aia.voiceinsights.api.knowledge.KnowledgeStore;
import com.aia.voiceinsights.api.service.ProductVectorSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/** Admin "Knowledge base": add policy documents as a zip, see what is loaded, remove a product or document. */
@RestController
@RequestMapping("/api/admin/knowledge")
public class KnowledgeController {

    private final KnowledgeIngestService ingest;
    private final KnowledgeStore store;
    private final ProductVectorSearchService search;

    public KnowledgeController(KnowledgeIngestService ingest, KnowledgeStore store, ProductVectorSearchService search) {
        this.ingest = ingest;
        this.store = store;
        this.search = search;
    }

    @GetMapping(value = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KnowledgeStore.ProductRow> products() { return store.products(); }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file,
                                    @RequestParam(name = "replaceAll", defaultValue = "false") boolean replaceAll) throws Exception {
        String name = file.getOriginalFilename();
        if (file.isEmpty() || name == null || !name.toLowerCase().endsWith(".zip"))
            return ResponseEntity.badRequest().body(Map.of("error", "Choose a .zip file."));
        // Spool the upload to disk (it can be hundreds of MB); the ingest job owns the file and deletes it when done.
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("knowledge-upload-", ".zip");
        try {
            file.transferTo(tmp);
            return ResponseEntity.accepted().body(ingest.start(name, tmp, replaceAll));
        } catch (Exception e) {
            java.nio.file.Files.deleteIfExists(tmp);
            if (e instanceof IllegalStateException) return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
            throw e;
        }
    }

    @GetMapping(value = "/jobs", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<KnowledgeIngestService.Job> jobs() { return ingest.recent(); }

    @GetMapping(value = "/jobs/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> job(@PathVariable String id) {
        var j = ingest.job(id);
        return j == null ? ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Unknown upload")) : ResponseEntity.ok(j);
    }

    @DeleteMapping(value = "/products", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteProduct(@RequestParam String product) {
        int n = store.deleteProduct(product);
        search.refreshProductNames();
        return Map.of("removed", n);
    }

    @DeleteMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> deleteDocument(@RequestParam String product, @RequestParam String category, @RequestParam String file) {
        int n = store.deleteDocument(product, category, file);
        search.refreshProductNames();
        return Map.of("removed", n);
    }
}
