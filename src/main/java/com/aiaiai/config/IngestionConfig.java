package com.aiaiai.config;

import com.aiaiai.ingestion.SectionAwareSplitter;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class IngestionConfig {

    @Value("${aiaiai.ingestion.max-segment-size}")
    private int maxSegmentSize;

    @Value("${aiaiai.ingestion.max-overlap-size}")
    private int maxOverlapSize;

    @Bean
    @Primary
    public EmbeddingStoreIngestor embeddingStoreIngestor(
            EmbeddingModel embeddingModel,
            @Qualifier("knowledgeStore") EmbeddingStore<TextSegment> knowledgeStore) {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(new SectionAwareSplitter(maxSegmentSize, maxOverlapSize))
                .embeddingModel(embeddingModel)
                .embeddingStore(knowledgeStore)
                .build();
    }

    @Bean(name = "embeddingStoreIngestorV2")
    public EmbeddingStoreIngestor embeddingStoreIngestorV2(
            @Qualifier("embeddingModelV2") EmbeddingModel embeddingModel,
            @Qualifier("knowledgeStoreV2") EmbeddingStore<TextSegment> knowledgeStore,
            @Value("${aiaiai.ingestion-v2.max-segment-size}") int maxSegmentSizeV2,
            @Value("${aiaiai.ingestion-v2.max-overlap-size}") int maxOverlapSizeV2) {
        return EmbeddingStoreIngestor.builder()
                .documentSplitter(new SectionAwareSplitter(maxSegmentSizeV2, maxOverlapSizeV2))
                .embeddingModel(embeddingModel)
                .embeddingStore(knowledgeStore)
                .build();
    }
}
