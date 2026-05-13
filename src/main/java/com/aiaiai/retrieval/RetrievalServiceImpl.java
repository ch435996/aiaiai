package com.aiaiai.retrieval;

import com.aiaiai.controller.dto.RetrievalSnippet;
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
            Pattern.compile("--- 结果 \\d+/\\d+ \\(相关度: ([\\d.]+), 置信度: (\\w+)\\) ---\\n([\\s\\S]*?)(?=\\n--- 结果 \\d+ |\\n?$)");

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> knowledgeStore;
    private final int topK;
    private final double minScore;

    public RetrievalServiceImpl(
            EmbeddingModel embeddingModel,
            @Qualifier("knowledgeStore") EmbeddingStore<TextSegment> knowledgeStore,
            @Value("${aiaiai.retrieval.top-k}") int topK,
            @Value("${aiaiai.retrieval.min-score}") double minScore) {
        this.embeddingModel = embeddingModel;
        this.knowledgeStore = knowledgeStore;
        this.topK = topK;
        this.minScore = minScore;
    }

    @Override
    public String search(String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchResult<TextSegment> result = knowledgeStore.search(
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

        // Java 层二次过滤，只喂 ≥0.70 的结果给 LLM
        List<EmbeddingMatch<TextSegment>> qualified = new ArrayList<>();
        for (EmbeddingMatch<TextSegment> match : matches) {
            if (match.score() >= 0.70) {
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
            sb.append(String.format("--- 结果 %d/%d (相关度: %.2f, 置信度: %s) ---\n",
                    i + 1, total, match.score(), normalizeConfidence(match.score())));
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

    @Override
    public String normalizeConfidence(double score) {
        if (score >= 0.85) return "HIGH";
        if (score >= 0.70) return "MEDIUM";
        return "LOW";
    }
}
