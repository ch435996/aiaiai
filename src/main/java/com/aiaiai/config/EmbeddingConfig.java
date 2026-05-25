package com.aiaiai.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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
    @Primary
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

    @Bean(name = "embeddingModelV2")
    @Qualifier("embeddingModelV2")
    public EmbeddingModel embeddingModelV2(
            @Value("${aiaiai.embedding-v2.base-url}") String baseUrlV2,
            @Value("${aiaiai.embedding-v2.api-key}") String apiKeyV2,
            @Value("${aiaiai.embedding-v2.model-name}") String modelNameV2,
            @Value("${aiaiai.embedding-v2.dimensions}") int dimensionsV2) {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKeyV2)
                .baseUrl(baseUrlV2)
                .modelName(modelNameV2)
                .dimensions(dimensionsV2)
                .maxSegmentsPerBatch(10)
                .timeout(Duration.ofSeconds(60))
                .build();
    }
}
