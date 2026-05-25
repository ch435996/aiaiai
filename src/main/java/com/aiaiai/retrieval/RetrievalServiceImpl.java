package com.aiaiai.retrieval;

import com.aiaiai.controller.dto.RetrievalSnippet;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
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
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class RetrievalServiceImpl implements RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalServiceImpl.class);
    private static final Pattern RESULT_PATTERN =
            Pattern.compile("--- 结果 \\d+/\\d+ \\(相关度: ([\\d.]+), 置信度: (\\w+)(?:, 章节: .*?)?\\) ---\\n([\\s\\S]*?)(?=\\n--- 结果 \\d+ |\\n?$)");

    private final EmbeddingModel embeddingModel;
    private final EmbeddingModel embeddingModelV2;
    private final EmbeddingStore<TextSegment> knowledgeStore;
    private final EmbeddingStore<TextSegment> knowledgeStoreV2;
    private final int topK;
    private final double minScore;          // Pinecone 层过滤（0.0=不做过滤，让 Java 层接管）
    private final double minScoreJava;       // V1 Java 层二次过滤阈值
    private final double highConfidence;     // V1 HIGH 置信度门槛
    private final double minScoreJavaV2;     // V2 Java 层二次过滤阈值
    private final double highConfidenceV2;   // V2 HIGH 置信度门槛
    private final String embeddingVersion;

    public RetrievalServiceImpl(
            EmbeddingModel embeddingModel,
            @Qualifier("embeddingModelV2") EmbeddingModel embeddingModelV2,
            @Qualifier("knowledgeStore") EmbeddingStore<TextSegment> knowledgeStore,
            @Qualifier("knowledgeStoreV2") EmbeddingStore<TextSegment> knowledgeStoreV2,
            @Value("${aiaiai.retrieval.top-k}") int topK,
            @Value("${aiaiai.retrieval.min-score}") double minScore,
            @Value("${aiaiai.retrieval.min-score-java}") double minScoreJava,
            @Value("${aiaiai.retrieval.high-confidence}") double highConfidence,
            @Value("${aiaiai.retrieval.min-score-java-v2}") double minScoreJavaV2,
            @Value("${aiaiai.retrieval.high-confidence-v2}") double highConfidenceV2,
            @Value("${aiaiai.retrieval.embedding-version}") String embeddingVersion) {
        this.embeddingModel = embeddingModel;
        this.embeddingModelV2 = embeddingModelV2;
        this.knowledgeStore = knowledgeStore;
        this.knowledgeStoreV2 = knowledgeStoreV2;
        this.topK = topK;
        this.minScore = minScore;
        this.minScoreJava = minScoreJava;
        this.highConfidence = highConfidence;
        this.minScoreJavaV2 = minScoreJavaV2;
        this.highConfidenceV2 = highConfidenceV2;
        this.embeddingVersion = embeddingVersion;
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
        Embedding queryEmbedding = activeModel().embed(query).content();
        // minScore 是 Pinecone 层过滤（0.0），Java 层过滤用 effectiveMinScore()
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
            return "[检索结果] 未在知识库中找到相关内容。";
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
            sb.append(String.format("--- 结果 %d/%d (相关度: %.2f, 置信度: %s, 章节: %s) ---\n",
                    i + 1, total, match.score(), normalizeConfidence(match.score()),
                    section.isEmpty() ? "—" : section));
            sb.append(match.embedded().text()).append("\n");
        }
        return sb.toString();
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
