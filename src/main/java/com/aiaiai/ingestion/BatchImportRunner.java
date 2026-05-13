package com.aiaiai.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.Arrays;

@Component
@Profile("batch-import")
public class BatchImportRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchImportRunner.class);

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024;

    private final PdfExtractionService pdfExtractionService;
    private final EmbeddingStoreIngestor ingestor;

    @Value("${aiaiai.ingestion.pdf-directory}")
    private String pdfDirectory;

    public BatchImportRunner(PdfExtractionService pdfExtractionService,
                             EmbeddingStoreIngestor ingestor) {
        this.pdfExtractionService = pdfExtractionService;
        this.ingestor = ingestor;
    }

    @Override
    public void run(ApplicationArguments args) {
        File dir = new File(pdfDirectory);
        if (!dir.exists() || !dir.isDirectory()) {
            log.error("PDF directory not found: {}", pdfDirectory);
            return;
        }

        File[] pdfFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
        if (pdfFiles == null || pdfFiles.length == 0) {
            log.warn("No PDF files found in: {}", pdfDirectory);
            return;
        }

        Arrays.sort(pdfFiles);
        log.info("=== Batch PDF Import ===");
        log.info("Directory: {}", pdfDirectory);
        log.info("Files found: {}", pdfFiles.length);

        int success = 0;
        int failed = 0;
        int skipped = 0;

        for (File pdfFile : pdfFiles) {
            int idx = success + failed + skipped + 1;
            log.info("Processing [{}/{}]: {}", idx, pdfFiles.length, pdfFile.getName());

            if (!isValidFile(pdfFile)) {
                skipped++;
                continue;
            }

            try {
                String text = pdfExtractionService.extract(pdfFile);
                if (text.isBlank()) {
                    log.warn("Skipped (no extractable text): {}", pdfFile.getName());
                    skipped++;
                    continue;
                }

                Metadata metadata = new Metadata();
                metadata.put("title", pdfFile.getName());
                metadata.put("source", pdfFile.getAbsolutePath());

                Document document = Document.from(text, metadata);
                ingestor.ingest(document);

                success++;
                log.info("Ingested [{}/{}]: {} ({} chars)",
                        success, pdfFiles.length - skipped, pdfFile.getName(), text.length());
            } catch (Exception e) {
                failed++;
                log.error("Failed [{}/{}]: {} — {}", idx, pdfFiles.length,
                        pdfFile.getName(), e.toString());
            }
        }

        log.info("=== Batch Import Complete: {} success, {} failed, {} skipped ===",
                success, failed, skipped);
    }

    private boolean isValidFile(File file) {
        if (!file.exists()) {
            log.warn("Skipped (not found): {}", file.getName());
            return false;
        }
        if (!file.isFile()) {
            log.warn("Skipped (not a regular file): {}", file.getName());
            return false;
        }
        if (!file.canRead()) {
            log.warn("Skipped (not readable): {}", file.getName());
            return false;
        }
        if (file.length() == 0) {
            log.warn("Skipped (empty): {}", file.getName());
            return false;
        }
        if (file.length() > MAX_FILE_SIZE) {
            log.warn("Skipped (too large, {} MB): {}",
                    file.length() / (1024 * 1024), file.getName());
            return false;
        }
        return true;
    }
}
