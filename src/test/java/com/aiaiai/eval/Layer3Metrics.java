package com.aiaiai.eval;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Static metric computation for Layer 3 Generation Anti-Hallucination evaluation.
 * Extracted to keep the main eval runner under 400 lines.
 */
final class Layer3Metrics {

    private Layer3Metrics() {}

    static final Pattern CITATION_PATTERN =
            Pattern.compile("来源：\\[?引用来源\\s*#?(\\d+)\\]?");
    static final Pattern CITATION_PATTERN_OLD =
            Pattern.compile("来源：知识库检索结果 #(\\d+)");

    static final List<String> UNCERTAINTY_MARKERS = List.of(
            "不确定", "无法确认", "未找到相关", "暂时无法", "缺少相关",
            "没有足够", "建议查阅原始", "根据目前知识库中的资料，暂时无法完整回答");

    static final List<String> BOUNDARY_MARKERS = List.of(
            "超出", "不在.*范围", "不属于", "建议.*其他.*工具",
            "知识库.*不包含", "领域边界", "无法.*超出");

    // ── M2: Hard Entity Precision ──

    static Set<String> extractHardEntities(String text) {
        Set<String> entities = new LinkedHashSet<>();
        Pattern numPattern = Pattern.compile(
                "\\b(\\d+(?:\\.\\d+)?(?:%|k|K|M)?)\\s*(?:个|层|维|点|邻居|epoch|batch|lr|GPU|MLP|prototype|head|block|stage|step|倍|万|亿)?",
                Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = numPattern.matcher(text);
        while (m.find()) {
            String entity = m.group().strip();
            if (entity.length() >= 2) entities.add(entity);
        }

        Pattern methodPattern = Pattern.compile(
                "\\b([A-Z][a-z]+(?:[A-Z][a-z]+)+|[A-Z]{2,}(?:-[A-Z][a-z]+)?|[A-Z][a-z]*Net|[A-Z][a-z]*Former|[A-Z][a-z]*Tr|[A-Z][a-z]*Diff[^\\s]*)\\b");
        m = methodPattern.matcher(text);
        while (m.find()) {
            String entity = m.group(1);
            if (entity.length() >= 3) entities.add(entity);
        }

        Pattern hyperPattern = Pattern.compile(
                "(?:batch.?size|learning.?rate|optimizer|weight.?decay|dropout|temperature|top.?[kp]|"
                        + "学习率|批量|dropout|优化器|衰减|温度|维度|dimension|dim)");
        m = hyperPattern.matcher(text);
        while (m.find()) entities.add(m.group());

        return entities;
    }

    static int countVerifiedEntities(Set<String> entities, List<String> chunks,
                                      SynonymExpander expander) {
        int count = 0;
        for (String entity : entities) {
            Set<String> expanded = expander.expand(entity);
            boolean found = false;
            for (String chunk : chunks) {
                String lower = chunk.toLowerCase();
                for (String exp : expanded) {
                    if (lower.contains(exp.toLowerCase())) { found = true; break; }
                }
                if (found) break;
            }
            if (found) count++;
        }
        return count;
    }

    // ── M3: Citation Recall & Precision ──

    static List<String> extractCitationMarkers(String answer) {
        List<String> markers = new ArrayList<>();
        java.util.regex.Matcher m = CITATION_PATTERN.matcher(answer);
        while (m.find()) markers.add(m.group());
        // also check old format
        java.util.regex.Matcher m2 = CITATION_PATTERN_OLD.matcher(answer);
        while (m2.find()) markers.add(m2.group());
        return markers;
    }

    static int countFactualClaims(String answer) {
        int count = 0;
        for (String sentence : splitSentences(answer)) {
            String s = sentence.strip();
            if (s.isEmpty()) continue;
            if (Pattern.compile("[0-9]").matcher(s).find()) count++;
            else if (Pattern.compile("[A-Z]{2,}").matcher(s).find()) count++;
            else if (CITATION_PATTERN.matcher(s).find()) count++;
            else if (CITATION_PATTERN_OLD.matcher(s).find()) count++;
        }
        return Math.max(count, 1);
    }

    static int countCorrectCitations(String answer, List<String> citations,
                                      List<String> chunks, SynonymExpander expander) {
        int correct = 0;
        for (String cit : citations) {
            int idx = -1;
            // try new format first: "来源：[引用来源 #N]" or "来源：引用来源#N"
            java.util.regex.Matcher m = CITATION_PATTERN.matcher(cit);
            if (m.find()) {
                try { idx = Integer.parseInt(m.group(1)) - 1; }
                catch (NumberFormatException e) { continue; }
            } else {
                // try old format: "来源：知识库检索结果 #N"
                java.util.regex.Matcher m2 = CITATION_PATTERN_OLD.matcher(cit);
                if (m2.find()) {
                    try { idx = Integer.parseInt(m2.group(1)) - 1; }
                    catch (NumberFormatException e) { continue; }
                } else {
                    continue;
                }
            }
            if (idx < 0 || idx >= chunks.size()) continue;

            int citPos = answer.indexOf(cit);
            String preceding = citPos > 0 ? answer.substring(Math.max(0, citPos - 200), citPos) : "";
            Set<String> claimEntities = extractHardEntities(preceding);

            if (claimEntities.isEmpty()) { correct++; continue; }

            String chunkText = chunks.get(idx).toLowerCase();
            boolean found = false;
            for (String entity : claimEntities) {
                for (String exp : expander.expand(entity)) {
                    if (chunkText.contains(exp.toLowerCase())) { found = true; break; }
                }
                if (found) break;
            }
            if (found) correct++;
        }
        return correct;
    }

    // ── M4: Uncertainty Calibration ──

    static boolean hasUncertaintyMarker(String answer) {
        return UNCERTAINTY_MARKERS.stream().anyMatch(answer::contains);
    }

    // ── M5: Domain Boundary Adherence ──

    static boolean hasBoundaryMarker(String answer) {
        if (answer.length() < 300
                && !Pattern.compile("来源：\\[?引用来源|来源：知识库检索结果").matcher(answer).find()) {
            return true;
        }
        return BOUNDARY_MARKERS.stream()
                .anyMatch(m -> Pattern.compile(m).matcher(answer).find());
    }

    // ── M6: Groundedness ──

    private static final List<String> META_DISCOURSE_PATTERNS = List.of(
            "根据目前知识库中的资料",
            "以下是我基于现有信息能提供的部分",
            "根据知识库中的资料",
            "根据检索结果",
            "以下是基于",
            "> 来源：",
            "来源：",
            "引用来源 #");

    static double computeGroundedness(List<String> sentences, List<Embedding> chunkEmbs,
                                       EmbeddingModelAdapter embModel) {
        if (sentences.isEmpty() || chunkEmbs.isEmpty()) return 0;
        double total = 0;
        int count = 0;
        for (String sentence : sentences) {
            String s = sentence.strip();
            if (s.length() < 10) continue;
            if (isMetaDiscourse(s)) continue;
            try {
                Embedding sentEmb = embModel.embed(s);
                double maxSim = chunkEmbs.stream()
                        .mapToDouble(ch -> cosineSimilarity(sentEmb, ch))
                        .max().orElse(0);
                total += maxSim;
                count++;
            } catch (Exception e) { /* skip */ }
        }
        return count > 0 ? total / count : 0;
    }

    private static boolean isMetaDiscourse(String sentence) {
        for (String pattern : META_DISCOURSE_PATTERNS) {
            if (sentence.contains(pattern)) return true;
        }
        return false;
    }

    static double cosineSimilarity(Embedding a, Embedding b) {
        float[] va = toFloatArray(a.vectorAsList());
        float[] vb = toFloatArray(b.vectorAsList());
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < va.length; i++) {
            dot += (double) va[i] * vb[i];
            normA += (double) va[i] * va[i];
            normB += (double) vb[i] * vb[i];
        }
        return (normA > 0 && normB > 0) ? dot / (Math.sqrt(normA) * Math.sqrt(normB)) : 0;
    }

    private static float[] toFloatArray(List<Float> list) {
        float[] arr = new float[list.size()];
        for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
        return arr;
    }

    // ── M1: Faithfulness ──

    static List<String> extractAtomicClaims(String answer) {
        List<String> claims = new ArrayList<>();
        for (String sentence : splitSentences(answer)) {
            String s = sentence.strip();
            if (s.isEmpty() || s.startsWith(">") || s.startsWith("---")) continue;
            if (Pattern.compile("[0-9]").matcher(s).find()
                    || Pattern.compile("[A-Z][a-z]+(?:[A-Z][a-z]+)+").matcher(s).find()
                    || Pattern.compile(
                        "(?:模块|架构|方法|策略|损失|模型|网络|训练|推理|编码|解码|特征|注意力|transformer|attention|embedding|layer|block|stage|module|loss|point|cloud|补全|重建)").matcher(s).find()) {
                claims.add(s);
            }
        }
        return claims;
    }

    static int countDeterministicSupported(List<String> claims, List<String> chunks,
                                            SynonymExpander expander) {
        int supported = 0;
        for (String claim : claims) {
            Set<String> entities = extractHardEntities(claim);
            if (entities.isEmpty()) { supported++; continue; }
            if (countVerifiedEntities(entities, chunks, expander) == entities.size()) supported++;
        }
        return supported;
    }

    // ── Utilities ──

    static List<String> splitSentences(String text) {
        return Arrays.asList(text.split("(?<=[。！？.!?\\n])\\s*"));
    }

    static String normalizeCosConf(double score) {
        return score >= 0.85 ? "HIGH" : score >= 0.70 ? "MEDIUM" : "LOW";
    }

    static String normalizeRerankConf(double score) {
        return score >= 0.70 ? "HIGH" : score >= 0.30 ? "MEDIUM" : "LOW";
    }

    static String labelOf(EmbeddingMatch<TextSegment> match) {
        var meta = match.embedded().metadata();
        String section = meta != null ? meta.getString("section") : null;
        return section != null ? section : "";
    }

    /**
     * Functional interface to abstract embedding model calls.
     * Lets us pass either EmbeddingModel or a lambda.
     */
    @FunctionalInterface
    interface EmbeddingModelAdapter {
        Embedding embed(String text);
    }
}
