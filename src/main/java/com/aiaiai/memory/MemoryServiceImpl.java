package com.aiaiai.memory;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class MemoryServiceImpl implements MemoryService {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> longTermMemoryStore;
    private final double minScore;

    public MemoryServiceImpl(
            EmbeddingModel embeddingModel,
            @Qualifier("longTermMemoryStore") EmbeddingStore<TextSegment> longTermMemoryStore,
            @Value("${aiaiai.memory.long-term.min-score}") double minScore) {
        this.embeddingModel = embeddingModel;
        this.longTermMemoryStore = longTermMemoryStore;
        this.minScore = minScore;
    }

    @Override
    public void save(String content) {
        Embedding embedding = embeddingModel.embed(content).content();
        longTermMemoryStore.add(embedding, TextSegment.from(content));
    }

    @Override
    public String recall(String query) {
        MemoryRecallResult result = recallStructured(query);
        if (result.count() == 0) {
            return "[长期记忆] 未找到相关记忆。";
        }
        StringBuilder sb = new StringBuilder("[长期记忆召回]\n");
        for (var entry : result.memories()) {
            sb.append(String.format("--- 记忆 %d (相关度: %.2f) ---\n",
                    entry.index(), entry.score()));
            sb.append(entry.text()).append("\n");
        }
        return sb.toString();
    }

    @Override
    public MemoryRecallResult recallStructured(String query) {
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchResult<TextSegment> result = longTermMemoryStore.search(
                EmbeddingSearchRequest.builder()
                        .queryEmbedding(queryEmbedding)
                        .maxResults(5)
                        .minScore(minScore)
                        .build());

        List<EmbeddingMatch<TextSegment>> matches = result.matches();
        if (matches.isEmpty()) {
            return new MemoryRecallResult(List.of(), 0);
        }

        List<MemoryRecallResult.Entry> entries = new ArrayList<>();
        for (int i = 0; i < matches.size(); i++) {
            EmbeddingMatch<TextSegment> match = matches.get(i);
            entries.add(new MemoryRecallResult.Entry(
                    i + 1, match.score(), match.embedded().text()));
        }
        return new MemoryRecallResult(entries, entries.size());
    }
}
