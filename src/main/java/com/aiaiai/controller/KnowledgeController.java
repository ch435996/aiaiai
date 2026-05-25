package com.aiaiai.controller;

import com.aiaiai.controller.dto.IngestRequest;
import com.aiaiai.ingestion.PdfExtractionService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final EmbeddingStoreIngestor ingestor;
    private final EmbeddingStoreIngestor ingestorV2;
    private final PdfExtractionService pdfExtractionService;

    public KnowledgeController(EmbeddingStoreIngestor ingestor,
                               @Qualifier("embeddingStoreIngestorV2") EmbeddingStoreIngestor ingestorV2,
                               PdfExtractionService pdfExtractionService) {
        this.ingestor = ingestor;
        this.ingestorV2 = ingestorV2;
        this.pdfExtractionService = pdfExtractionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest(@RequestBody IngestRequest request,
                                                       @RequestParam(value = "version", required = false, defaultValue = "v1") String version) {
        Metadata metadata = new Metadata();
        if (request.getTitle() != null && !request.getTitle().isBlank()) {
            metadata.put("title", request.getTitle());
        }
        if (request.getSource() != null && !request.getSource().isBlank()) {
            metadata.put("source", request.getSource());
        }

        Document document = Document.from(request.getContent(), metadata);
        EmbeddingStoreIngestor target = "v2".equals(version) ? ingestorV2 : ingestor;
        target.ingest(document);

        return ResponseEntity.ok(Map.of(
                "status", "ok",
                "message", "文档已成功摄入知识库",
                "version", version
        ));
    }

    @PostMapping("/ingest/pdf")
    public ResponseEntity<Map<String, Object>> ingestPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "source", required = false) String source,
            @RequestParam(value = "version", required = false, defaultValue = "v1") String version) {
        try {
            String text = pdfExtractionService.extract(file.getInputStream());
            if (text.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "status", "error",
                        "message", "PDF 中未提取到文本内容"
                ));
            }

            Metadata metadata = new Metadata();
            String docTitle = (title != null && !title.isBlank())
                    ? title : file.getOriginalFilename();
            metadata.put("title", docTitle);
            if (source != null && !source.isBlank()) {
                metadata.put("source", source);
            }

            Document document = Document.from(text, metadata);
            EmbeddingStoreIngestor target = "v2".equals(version) ? ingestorV2 : ingestor;
            target.ingest(document);

            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "message", "PDF 已成功摄入知识库",
                    "title", docTitle,
                    "chars", text.length(),
                    "version", version
            ));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", "PDF 处理失败: " + e.getMessage()
            ));
        }
    }
}
