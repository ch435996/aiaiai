package com.aiaiai.ingestion;

import jakarta.annotation.PreDestroy;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class PdfExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractionService.class);

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int EXTRACTION_TIMEOUT_SECONDS = 30;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    public String extract(File file) throws IOException {
        validateFile(file);
        log.info("Extracting text from PDF: {} ({} bytes)", file.getName(), file.length());
        return extractWithTimeout(() -> doExtract(file), file.getName());
    }

    public String extract(InputStream inputStream) throws IOException {
        return extractWithTimeout(() -> doExtract(inputStream), "input stream");
    }

    private String extractWithTimeout(Callable<String> task, String fileLabel) throws IOException {
        Future<String> future = executor.submit(task);
        try {
            String text = future.get(EXTRACTION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            log.info("Extracted {} characters from {}", text.length(), fileLabel);
            return text;
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("PDF extraction timed out after " + EXTRACTION_TIMEOUT_SECONDS + "s: " + fileLabel);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IOException("PDF extraction interrupted: " + fileLabel);
        } catch (Exception e) {
            throw new IOException("PDF extraction failed: " + fileLabel + " — " + e.getMessage(), e);
        }
    }

    private String doExtract(File file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file)) {
            return stripText(document);
        }
    }

    private String doExtract(InputStream inputStream) throws IOException {
        try (PDDocument document = Loader.loadPDF(new RandomAccessReadBuffer(inputStream))) {
            return stripText(document);
        }
    }

    private String stripText(PDDocument document) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setAddMoreFormatting(false);
        return stripper.getText(document);
    }

    private void validateFile(File file) throws IOException {
        if (!file.exists()) {
            throw new IOException("File not found: " + file.getAbsolutePath());
        }
        if (!file.isFile()) {
            throw new IOException("Not a regular file: " + file.getAbsolutePath());
        }
        if (!file.canRead()) {
            throw new IOException("File not readable: " + file.getAbsolutePath());
        }
        if (file.length() == 0) {
            throw new IOException("File is empty: " + file.getName());
        }
        if (file.length() > MAX_FILE_SIZE) {
            throw new IOException(String.format(
                    "File too large: %s (%.1f MB, max %.0f MB)",
                    file.getName(), file.length() / (1024.0 * 1024.0), MAX_FILE_SIZE / (1024.0 * 1024.0)));
        }
    }
}
