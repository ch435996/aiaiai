package com.aiaiai.config;

import com.aiaiai.reranker.DashScopeReranker;
import dev.langchain4j.model.scoring.ScoringModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RerankerConfig {

    @Bean
    public ScoringModel scoringModel(
            @Value("${aiaiai.reranker.base-url}") String baseUrl,
            @Value("${aiaiai.reranker.api-key}") String apiKey,
            @Value("${aiaiai.reranker.model-name}") String modelName) {
        return new DashScopeReranker(baseUrl, apiKey, modelName);
    }
}
