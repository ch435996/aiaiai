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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PdfExtractionService {

    private static final Logger log = LoggerFactory.getLogger(PdfExtractionService.class);

    private static final long MAX_FILE_SIZE = 100 * 1024 * 1024; // 100MB
    private static final int EXTRACTION_TIMEOUT_SECONDS = 30;

    private static final Pattern REFERENCE_HEADER = Pattern.compile(
        "(?<=\\n\\n)(References|REFERENCES|Bibliography|BIBLIOGRAPHY|"
        + "References and Notes|Acknowledgments|ACKNOWLEDGMENTS|"
        + "参考文献|文献)"
        + "(\\s|$)"
    );

    // 保护 LaTeX 显示公式：$$...$$ 和 \[...\]
    private static final Pattern MATH_DISPLAY = Pattern.compile(
        Pattern.quote("$$") + "(.*?)" + Pattern.quote("$$"), Pattern.DOTALL);
    private static final Pattern MATH_BRACKET = Pattern.compile(
        Pattern.quote("\\[") + "(.*?)" + Pattern.quote("\\]"), Pattern.DOTALL);

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
        String raw = stripper.getText(document);
        return removeReferences(cleanText(raw));
    }

    /** 清洗 PDF 提取文本：修复物理断行、去除页眉页码、保护 LaTeX 公式 */
    private String cleanText(String raw) {
        String text = raw.replace("\r\n", "\n").replace("\r", "\n");
        text = protectMathBlocks(text);
        text = detectParagraphBreaks(text);
        text = text.replaceAll("\n{3,}", "\n\n");

        String[] paragraphs = text.split("\n\n");
        StringBuilder result = new StringBuilder();

        for (String para : paragraphs) {
            String cleaned = para.replace("\n", " ")
                    .replaceAll("\\s+", " ")
                    .trim();
            if (cleaned.isEmpty()) continue;
            if (isNoise(cleaned)) continue;
            result.append(cleaned).append("\n\n");
        }
        return result.toString().trim();
    }

    /**
     * PDFBox 提取的文本通常每行一个 \n，不区分段落内换行和段落间换行。
     * 三重启发式：①空行 → 段落边界；②上一行句末标点 + 下一行大写/数字开头 → 段落边界；
     * ③下一行自身像 section header → 段落边界。
     */
    private String detectParagraphBreaks(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(lines[i]);
            if (i < lines.length - 1) {
                String cur = lines[i].trim();
                String nxt = (i + 1 < lines.length) ? lines[i + 1].trim() : "";
                if (cur.isEmpty() || nxt.isEmpty()) {
                    sb.append("\n\n");
                } else if (isParaBoundary(cur, nxt) || looksLikeSectionStart(nxt)) {
                    sb.append("\n\n");
                } else {
                    sb.append("\n");
                }
            }
        }
        return sb.toString();
    }

    /** 判断当前行与下一行之间是否为段落边界 */
    private boolean isParaBoundary(String curLine, String nextLine) {
        char last = curLine.charAt(curLine.length() - 1);
        if (last != '.' && last != '?' && last != '!' && last != '"' && last != ')' && last != ']')
            return false;
        char first = nextLine.charAt(0);
        return Character.isUpperCase(first) || Character.isDigit(first)
                || first == '[' || first == '(';
    }

    /** 快速判断一行是否像 section header（编号模式或常见章节词） */
    private boolean looksLikeSectionStart(String line) {
        if (line.length() > 90) return false;
        // 编号模式：3. / 3.1 / 3.1.1 / IV.
        if (line.matches("^\\d+(?:\\.\\d+)*\\.?\\s+[A-Z].*")) return true;
        if (line.matches("^[IVX]+\\.\\s+[A-Z].*")) return true;
        // 常见章节词开头
        String lower = line.toLowerCase();
        String[] starts = {"abstract", "introduction", "related work", "background",
            "preliminaries", "method", "experiment", "result", "conclusion",
            "discussion", "appendix", "supplementary", "acknowledgment", "reference",
            "bibliography", "dataset", "evaluation", "overview", "summary"};
        for (String s : starts) {
            if (lower.startsWith(s)) return true;
        }
        return false;
    }

    /** 保护 LaTeX 数学块：将内部换行替换为空格，外围加空行隔离，避免行合并破坏公式 */
    private String protectMathBlocks(String text) {
        text = MATH_DISPLAY.matcher(text).replaceAll(mr -> {
            String inner = mr.group(1).replace("\n", " ").trim();
            return "\n\n$$\n" + inner + "\n$$\n\n";
        });
        text = MATH_BRACKET.matcher(text).replaceAll(mr -> {
            String inner = mr.group(1).replace("\n", " ").trim();
            return "\n\n\\[\n" + inner + "\n\\]\n\n";
        });
        return text;
    }

    /** 过滤页眉、页码、版权声明等噪声行 */
    private boolean isNoise(String text) {
        String t = text.trim();
        if (t.matches("^\\d{1,4}$")) return true;
        if (t.length() < 20 && t.contains("Copyright")) return true;
        if (t.matches("^arXiv:\\d+\\.\\d+.*")) return true;
        return false;
    }

    /** 截断参考文献/致谢章节（匹配作为独立章节标题的 header，避免误截正文中的提及） */
    private String removeReferences(String text) {
        Matcher m = REFERENCE_HEADER.matcher(text);
        if (m.find()) {
            return text.substring(0, m.start()).trim();
        }
        return text;
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
