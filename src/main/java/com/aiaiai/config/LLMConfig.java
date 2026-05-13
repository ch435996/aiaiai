package com.aiaiai.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LLMConfig {

    @Value("${aiaiai.llm.base-url}")
    private String baseUrl;

    @Value("${aiaiai.llm.api-key}")
    private String apiKey;

    @Value("${aiaiai.llm.model-name}")
    private String modelName;

    @Value("${aiaiai.llm.temperature}")
    private double temperature;

    @Value("${aiaiai.llm.max-tokens}")
    private int maxTokens;

    @Value("${aiaiai.llm.timeout-seconds}")
    private int timeoutSeconds;

    @Value("${aiaiai.llm.log-requests:true}")
    private boolean logRequests;

    @Value("${aiaiai.llm.log-responses:true}")
    private boolean logResponses;

    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .logRequests(logRequests)
                .logResponses(logResponses)
                .build();
    }
}
