package com.aiaiai.retrieval;

import com.aiaiai.controller.dto.RetrievalSnippet;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RetrievalServiceImpl implements RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalServiceImpl.class);
    private static final Pattern RESULT_PATTERN =
            Pattern.compile("--- \\[?引用来源\\s*#\\d+\\]? \\(相关度: ([\\d.]+), 置信度: (\\w+)(?:, 章节: .*?)?\\) ---\\n([\\s\\S]*?)(?=\\n--- \\[|\\n?$)");

    private final EmbeddingModel embeddingModel;
    private final EmbeddingModel embeddingModelV2;
    private final EmbeddingStore<TextSegment> knowledgeStore;
    private final EmbeddingStore<TextSegment> knowledgeStoreV2;
    private final ScoringModel reranker;
    private final int topK;
    private final double minScore;
    private final double minScoreJava;
    private final double highConfidence;
    private final double minScoreJavaV2;
    private final double highConfidenceV2;
    private final String embeddingVersion;
    private final int rerankFinalTopK;
    private final double rerankHighConfidence;
    private final double rerankMediumConfidence;

    public RetrievalServiceImpl(
            EmbeddingModel embeddingModel,
            @Qualifier("embeddingModelV2") EmbeddingModel embeddingModelV2,
            @Qualifier("knowledgeStore") EmbeddingStore<TextSegment> knowledgeStore,
            @Qualifier("knowledgeStoreV2") EmbeddingStore<TextSegment> knowledgeStoreV2,
            ScoringModel reranker,
            @Value("${aiaiai.retrieval.top-k}") int topK,
            @Value("${aiaiai.retrieval.min-score}") double minScore,
            @Value("${aiaiai.retrieval.min-score-java}") double minScoreJava,
            @Value("${aiaiai.retrieval.high-confidence}") double highConfidence,
            @Value("${aiaiai.retrieval.min-score-java-v2}") double minScoreJavaV2,
            @Value("${aiaiai.retrieval.high-confidence-v2}") double highConfidenceV2,
            @Value("${aiaiai.retrieval.embedding-version}") String embeddingVersion,
            @Value("${aiaiai.reranker.top-n-after-rerank:5}") int rerankFinalTopK,
            @Value("${aiaiai.reranker.high-confidence:0.70}") double rerankHighConfidence,
            @Value("${aiaiai.reranker.medium-confidence:0.30}") double rerankMediumConfidence) {
        this.embeddingModel = embeddingModel;
        this.embeddingModelV2 = embeddingModelV2;
        this.knowledgeStore = knowledgeStore;
        this.knowledgeStoreV2 = knowledgeStoreV2;
        this.reranker = reranker;
        this.topK = topK;
        this.minScore = minScore;
        this.minScoreJava = minScoreJava;
        this.highConfidence = highConfidence;
        this.minScoreJavaV2 = minScoreJavaV2;
        this.highConfidenceV2 = highConfidenceV2;
        this.embeddingVersion = embeddingVersion;
        this.rerankFinalTopK = rerankFinalTopK;
        this.rerankHighConfidence = rerankHighConfidence;
        this.rerankMediumConfidence = rerankMediumConfidence;
    }

    private EmbeddingModel activeModel() {
        return "v2".equals(embeddingVersion) ? embeddingModelV2 : embeddingModel;
    }

    private EmbeddingStore<TextSegment> activeStore() {
        return "v2".equals(embeddingVersion) ? knowledgeStoreV2 : knowledgeStore;
    }

    private double effectiveMinScore() {
        return "v2".equals(embeddingVersion) ? minScoreJavaV2 : minScoreJava;
    }

    private double effectiveHighConfidence() {
        return "v2".equals(embeddingVersion) ? highConfidenceV2 : highConfidence;
    }

    @Override
    public String search(String query) {
        return search(query, topK);
    }

    @Override
    public String search(String query, int topK) {
        Embedding queryEmbedding = activeModel().embed(query).content();
        EmbeddingSearchResult<TextSegment> result = activeStore().search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(topK)
                        .minScore(minScore)
                        .build());

        List<EmbeddingMatch<TextSegment>> matches = result.matches();
        log.debug("检索 '{}': topK={}, scores={}",
                query, matches.size(),
                matches.stream().map(m -> String.format("%.2f", m.score())).toList());

        if (matches.isEmpty()) {
            return "[检索结果] 未在知识库中找到相关内容。\n\n"
                    + "[系统指令] 检索无结果，必须使用不确定模板回答，禁止编造。";
        }

        double threshold = effectiveMinScore();
        List<EmbeddingMatch<TextSegment>> qualified = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            if (match.score() >= threshold) {
                qualified.add(match);
            }
        }
        if (qualified.isEmpty()) {
            return "[检索结果] 未在知识库中找到相关内容。";
        }

        int total = qualified.size();
        StringBuilder sb = new StringBuilder("[知识库检索结果]\n");
        for (int i = 0; i < total; i++) {
            EmbeddingMatch<TextSegment> match = qualified.get(i);
            String section = labelOf(match);
            sb.append(String.format("--- [引用来源 #%d] (相关度: %.2f, 置信度: %s, 章节: %s) ---\n",
                    i + 1, match.score(), normalizeConfidence(match.score()),
                    section.isEmpty() ? "—" : section));
            sb.append(match.embedded().text()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public SearchResult searchStructured(String query, int topK) {
        Embedding queryEmbedding = activeModel().embed(query).content();
        EmbeddingSearchResult<TextSegment> result = activeStore().search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(topK)
                        .minScore(minScore)
                        .build());

        List<EmbeddingMatch<TextSegment>> matches = result.matches();
        if (matches.isEmpty()) {
            return new SearchResult(List.of(), 0, 0);
        }

        double threshold = effectiveMinScore();
        List<SearchResult.SnippetEntry> entries = new ArrayList<>();
        double maxScore = 0;
        int idx = 0;
        for (EmbeddingMatch<TextSegment> match : matches) {
            if (match.score() >= threshold) {
                String section = labelOf(match);
                entries.add(new SearchResult.SnippetEntry(
                        ++idx, match.score(), normalizeConfidence(match.score()),
                        section.isEmpty() ? "" : section, match.embedded().text()));
                if (match.score() > maxScore) maxScore = match.score();
            }
        }
        return new SearchResult(entries, entries.size(), maxScore);
    }

    @Override
    public SearchResult rerankStructured(SearchResult result, String query, int finalTopK) {
        if (result.count() == 0) return result;

        List<TextSegment> segments = new ArrayList<>();
        for (var entry : result.snippets()) {
            segments.add(TextSegment.from(entry.text()));
        }

        Response<List<Double>> scoreResponse = reranker.scoreAll(segments, query);
        List<Double> scores = scoreResponse.content();

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) indices.add(i);
        indices.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

        int limit = Math.min(finalTopK, indices.size());
        List<SearchResult.SnippetEntry> reranked = new ArrayList<>();
        double maxScore = 0;
        for (int i = 0; i < limit; i++) {
            int origIdx = indices.get(i);
            double newScore = scores.get(origIdx);
            var orig = result.snippets().get(origIdx);
            reranked.add(new SearchResult.SnippetEntry(
                    i + 1, newScore, normalizeRerankConfidence(newScore),
                    orig.section(), orig.text()));
            if (newScore > maxScore) maxScore = newScore;
        }
        return new SearchResult(reranked, reranked.size(), maxScore);
    }

    @Override
    public String rerankAndTruncate(String resultText, String query, int finalTopK) {
        if (resultText == null || resultText.contains("未在知识库中找到")) {
            return resultText;
        }

        List<EmbeddingMatch<TextSegment>> parsed = parseResults(resultText);
        if (parsed.isEmpty()) return resultText;

        List<TextSegment> segments = new ArrayList<>();
        for (var m : parsed) {
            segments.add(m.embedded());
        }

        Response<List<Double>> scoreResponse = reranker.scoreAll(segments, query);
        List<Double> scores = scoreResponse.content();

        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < scores.size(); i++) indices.add(i);
        indices.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

        int limit = Math.min(finalTopK, indices.size());
        StringBuilder sb = new StringBuilder("[知识库检索结果（已精排）]\n");
        for (int i = 0; i < limit; i++) {
            int idx = indices.get(i);
            var match = parsed.get(idx);
            double newScore = scores.get(idx);
            String section = labelOf(match);
            sb.append(String.format("--- [引用来源 #%d] (相关度: %.2f, 置信度: %s, 章节: %s) ---\n",
                    i + 1, newScore,
                    normalizeRerankConfidence(newScore),
                    section.isEmpty() ? "—" : section));
            sb.append(match.embedded().text()).append("\n");
        }
        return sb.toString();
    }

    private List<EmbeddingMatch<TextSegment>> parseResults(String resultText) {
        List<EmbeddingMatch<TextSegment>> list = new ArrayList<>();
        Matcher m = RESULT_PATTERN.matcher(resultText);
        while (m.find()) {
            double score = Double.parseDouble(m.group(1));
            String text = m.group(3).trim();
            TextSegment seg = TextSegment.from(text);
            list.add(new EmbeddingMatch<>(score, "", null, seg));
        }
        return list;
    }

    private String normalizeRerankConfidence(double score) {
        if (score >= rerankHighConfidence) return "HIGH";
        if (score >= rerankMediumConfidence) return "MEDIUM";
        return "LOW";
    }

    @Override
    public List<RetrievalSnippet> extractSnippets(String resultText) {
        List<RetrievalSnippet> snippets = new ArrayList<>();
        if (resultText == null || resultText.contains("未在知识库中找到")) return snippets;

        Matcher m = RESULT_PATTERN.matcher(resultText);
        while (m.find() && snippets.size() < 2) {
            double score = Double.parseDouble(m.group(1));
            String fullText = m.group(3).trim();
            String preview = fullText.length() > 120 ? fullText.substring(0, 120) + "…" : fullText;
            snippets.add(new RetrievalSnippet(score, normalizeConfidence(score), preview, "知识库"));
        }
        return snippets;
    }

    private String labelOf(EmbeddingMatch<TextSegment> match) {
        Metadata meta = match.embedded().metadata();
        if (meta == null) return "";
        String section = meta.getString("section");
        return section != null ? section : "";
    }

    @Override
    public String normalizeConfidence(double score) {
        if (score >= effectiveHighConfidence()) return "HIGH";
        if (score >= effectiveMinScore()) return "MEDIUM";
        return "LOW";
    }
}
