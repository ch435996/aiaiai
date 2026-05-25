package com.aiaiai.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 结构感知文档切分器：先检测 section header 强制切分边界，section 内部按段落合并。
 *
 * <p>与 {@code DocumentBySentenceSplitter} 的区别：
 * <ul>
 *   <li>识别学术论文的章节标题（如 "3.1 Network Architecture"），在章节边界强制断块</li>
 *   <li>每个 chunk 的 metadata 注入 {@code section} 字段，检索时可溯源</li>
 *   <li>内部按段落合并（而非句子），段落是比句子更稳定的语义单元</li>
 * </ul>
 */
public class SectionAwareSplitter implements DocumentSplitter {

    private final int maxSegmentSize;
    private final int maxOverlapSize;

    // 句子边界：句末标点 + 空白 + 大写字母/数字
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[.!?]\"?)\\s+(?=[A-Z0-9\\[])");

    // 常见章节标题词（小写）
    private static final String[] SECTION_WORDS = {
        "abstract", "introduction", "related work", "background",
        "preliminaries", "method", "approach", "architecture",
        "network architecture", "proposed method", "experiments",
        "experimental results", "results", "evaluation", "ablation study",
        "ablation", "conclusion", "discussion", "future work",
        "limitations", "references", "bibliography", "acknowledgments",
        "acknowledgements", "appendix", "supplementary",
        "implementation details", "training details", "datasets",
        "dataset", "metrics", "evaluation metrics", "loss function",
        "training strategy", "data augmentation", "overview", "summary"
    };

    public SectionAwareSplitter(int maxSegmentSize, int maxOverlapSize) {
        this.maxSegmentSize = maxSegmentSize;
        this.maxOverlapSize = maxOverlapSize;
    }

    @Override
    public List<TextSegment> split(Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Metadata docMeta = document.metadata();
        String[] paragraphs = text.split("\n\n");
        List<SectionSpan> sections = detectSections(paragraphs);
        return chunkSections(paragraphs, sections, docMeta);
    }

    /** 扫描段落数组，识别 section header 并划分区段 */
    private List<SectionSpan> detectSections(String[] paragraphs) {
        List<SectionSpan> sections = new ArrayList<>();
        String currentHeading = "";
        int sectionStart = 0;

        for (int i = 0; i < paragraphs.length; i++) {
            String p = paragraphs[i].trim();
            if (isSectionHeader(p)) {
                if (i > sectionStart) {
                    sections.add(new SectionSpan(currentHeading, sectionStart, i));
                }
                currentHeading = normalizeHeading(p);
                sectionStart = i;
            }
        }
        // 最后一个 section
        if (sectionStart < paragraphs.length) {
            sections.add(new SectionSpan(currentHeading, sectionStart, paragraphs.length));
        }
        return sections;
    }

    /** 将各区段内的段落合并为 chunk，注入 section metadata */
    private List<TextSegment> chunkSections(String[] paragraphs,
                                             List<SectionSpan> sections,
                                             Metadata docMeta) {
        List<TextSegment> allSegments = new ArrayList<>();
        for (SectionSpan section : sections) {
            allSegments.addAll(chunkOneSection(paragraphs, section, docMeta));
        }
        return allSegments;
    }

    private List<TextSegment> chunkOneSection(String[] paragraphs,
                                               SectionSpan section,
                                               Metadata docMeta) {
        List<TextSegment> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (int i = section.startIdx; i < section.endIdx; i++) {
            String p = paragraphs[i].trim();
            if (p.isEmpty()) continue;

            // 跳过 section header 本身的段落（它只是标签，已在 metadata 中）
            if (i == section.startIdx && !section.heading.isEmpty()
                    && isSectionHeader(p)) {
                continue;
            }

            // 单个段落超过上限 → 先 flush 当前 buffer，再句子级切分
            if (p.length() > maxSegmentSize) {
                if (current.length() > 0) {
                    chunks.add(buildChunk(current.toString().trim(), section.heading, docMeta));
                    current = new StringBuilder();
                }
                chunks.addAll(splitLongParagraph(p, section.heading, docMeta));
                continue;
            }

            if (current.length() > 0 && current.length() + p.length() + 2 > maxSegmentSize) {
                chunks.add(buildChunk(current.toString().trim(), section.heading, docMeta));
                // overlap：取上一个 chunk 尾部作为下一个 chunk 的前缀
                String overlap = extractOverlap(current.toString(), maxOverlapSize);
                current = new StringBuilder(overlap);
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(p);
        }

        if (current.length() > 0) {
            chunks.add(buildChunk(current.toString().trim(), section.heading, docMeta));
        }
        return chunks;
    }

    /** 句子级切分超长段落，按 maxSegmentSize 合并句子为 chunk */
    private List<TextSegment> splitLongParagraph(String text, String heading, Metadata docMeta) {
        List<TextSegment> chunks = new ArrayList<>();
        String[] sentences = SENTENCE_BOUNDARY.split(text);
        StringBuilder current = new StringBuilder();

        for (String sentence : sentences) {
            String s = sentence.trim();
            if (s.isEmpty()) continue;
            if (current.length() > 0 && current.length() + s.length() + 1 > maxSegmentSize) {
                chunks.add(buildChunk(current.toString().trim(), heading, docMeta));
                String overlap = extractOverlap(current.toString(), maxOverlapSize);
                current = new StringBuilder(overlap);
            }
            if (current.length() > 0) current.append(" ");
            current.append(s);
        }
        if (current.length() > 0) {
            chunks.add(buildChunk(current.toString().trim(), heading, docMeta));
        }
        return chunks;
    }

    /** 从文本尾部取 overlap，在最近句边界处截断 */
    private String extractOverlap(String text, int overlapSize) {
        if (overlapSize <= 0 || text.length() <= overlapSize) return "";
        String tail = text.substring(Math.max(0, text.length() - overlapSize));
        int cut = -1;
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '.' || c == '!' || c == '?') cut = i;
        }
        if (cut > 0 && cut < tail.length() - 1) {
            return tail.substring(cut + 1).trim();
        }
        return "";
    }

    private TextSegment buildChunk(String text, String heading, Metadata docMeta) {
        Metadata meta = new Metadata();
        if (heading != null && !heading.isEmpty()) {
            meta.put("section", heading);
        }
        // 传播文档级元数据
        docMeta.toMap().forEach((k, v) -> {
            if (v != null && !meta.containsKey(k)) {
                meta.put(k, v.toString());
            }
        });
        return TextSegment.from(text, meta);
    }

    // ---------- section 检测 ----------

    /** 判断一个段落是否为 section header */
    private boolean isSectionHeader(String p) {
        if (p.length() > 120) return false;        // 太长，不可能是标题
        if (p.endsWith(".") && p.length() < 60) return false; // "3.1." 这种不算
        if (p.contains(",") && p.length() < 40) return false; // 排除 "Abstract, ..."

        String t = p.trim();

        // 编号模式：1. / 3.1 / 3.1.1 / IV.
        if (t.matches("^[IVX]+\\.[\\s\\S]*")) return true;
        if (t.matches("^\\d+(?:\\.\\d+)*\\.?\\s+[A-Z][\\s\\S]*")) return true;
        if (t.matches("^\\d+(?:\\.\\d+)*\\.?$")) return true;

        // 常见章节名（精确匹配或前缀匹配）
        String lower = t.toLowerCase().replaceAll("[^a-z\\s]", " ").replaceAll("\\s+", " ").trim();
        for (String word : SECTION_WORDS) {
            if (lower.equals(word)) return true;
            // 前缀匹配：如 "experiments and discussion" 匹配 "experiments"
            if (lower.length() >= word.length() + 3 && lower.startsWith(word + " ")) return true;
        }
        return false;
    }

    /** 规范化 heading：截取首 70 字符内的完整词 */
    private String normalizeHeading(String p) {
        String t = p.trim();
        int cut = Math.min(t.length(), 70);
        // 回退到最后一个完整词（不在词中间截断）
        while (cut > 20 && t.charAt(cut - 1) != ' ') cut--;
        return t.substring(0, cut).trim();
    }

    // ---------- 内部类型 ----------

    private static class SectionSpan {
        final String heading;
        final int startIdx;  // inclusive
        final int endIdx;    // exclusive

        SectionSpan(String heading, int startIdx, int endIdx) {
            this.heading = heading;
            this.startIdx = startIdx;
            this.endIdx = endIdx;
        }
    }
}
