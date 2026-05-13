package com.aiaiai.config;

import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IngestionConfig {

    @Value("${aiaiai.ingestion.max-segment-size}")
    private int maxSegmentSize;

    @Value("${aiaiai.ingestion.max-overlap-size}")
    private int maxOverlapSize;

    @Bean
    public EmbeddingStoreIngestor embeddingStoreIngestor(
            EmbeddingModel embeddingModel,
            @Qualifier("knowledgeStore") EmbeddingStore<TextSegment> knowledgeStore) {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(new DocumentByParagraphSplitter(maxSegmentSize, maxOverlapSize))
                .embeddingModel(embeddingModel)
                .embeddingStore(knowledgeStore)
                .build();
    }
}
