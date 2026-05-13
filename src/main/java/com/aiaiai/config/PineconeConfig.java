package com.aiaiai.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeServerlessIndexConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PineconeConfig {

    @Value("${aiaiai.pinecone.api-key}")
    private String apiKey;

    @Value("${aiaiai.pinecone.index}")
    private String index;

    @Value("${aiaiai.pinecone.namespace-knowledge}")
    private String knowledgeNamespace;

    @Value("${aiaiai.pinecone.namespace-memory}")
    private String memoryNamespace;

    @Value("${aiaiai.pinecone.cloud}")
    private String cloud;

    @Value("${aiaiai.pinecone.region}")
    private String region;

    @Value("${aiaiai.pinecone.dimension}")
    private int dimension;

    @Bean(name = "knowledgeStore")
    public EmbeddingStore<TextSegment> knowledgeStore() {
        return PineconeEmbeddingStore.builder()
                .apiKey(apiKey)
                .index(index)
                .nameSpace(knowledgeNamespace)
                .createIndex(PineconeServerlessIndexConfig.builder()
                        .cloud(cloud)
                        .region(region)
                        .dimension(dimension)
                        .build())
                .build();
    }

    @Bean(name = "longTermMemoryStore")
    public EmbeddingStore<TextSegment> longTermMemoryStore() {
        return PineconeEmbeddingStore.builder()
                .apiKey(apiKey)
                .index(index)
                .nameSpace(memoryNamespace)
                .createIndex(PineconeServerlessIndexConfig.builder()
                        .cloud(cloud)
                        .region(region)
                        .dimension(dimension)
                        .build())
                .build();
    }
}
