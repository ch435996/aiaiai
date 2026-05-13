package com.aiaiai.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class EmbeddingConfig {

    @Value("${aiaiai.embedding.base-url}")
    private String baseUrl;

    @Value("${aiaiai.embedding.api-key}")
    private String apiKey;

    @Value("${aiaiai.embedding.model-name}")
    private String modelName;

    @Value("${aiaiai.embedding.dimensions}")
    private int dimensions;

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .dimensions(dimensions)
                .maxSegmentsPerBatch(10)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
