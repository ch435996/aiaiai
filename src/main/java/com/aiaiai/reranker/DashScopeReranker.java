package com.aiaiai.reranker;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DashScopeReranker implements ScoringModel {

    private static final Logger log = LoggerFactory.getLogger(DashScopeReranker.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final HttpClient httpClient;
    private final String url;
    private final String apiKey;
    private final String modelName;

    public DashScopeReranker(String baseUrl, String apiKey, String modelName) {
        this.url = baseUrl.replaceAll("/$", "")
                + "/api/v1/services/rerank/text-rerank/text-rerank";
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public Response<List<Double>> scoreAll(List<TextSegment> segments, String query) {
        if (segments == null || segments.isEmpty()) {
            return Response.from(List.of());
        }

        List<String> documents = segments.stream()
                .map(s -> s.text().length() > 3500 ? s.text().substring(0, 3500) : s.text())
                .toList();

        try {
            Map<String, Object> body = Map.of(
                    "model", modelName,
                    "input", Map.of("query", query, "documents", documents),
                    "parameters", Map.of("top_n", segments.size(), "return_documents", false)
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.warn("Reranker API returned {}: {}", response.statusCode(),
                        response.body().substring(0, Math.min(response.body().length(), 200)));
                return Response.from(fallbackScores(segments.size()));
            }

            return Response.from(parseScores(response.body(), segments.size()));

        } catch (Exception e) {
            log.warn("Reranker call failed, falling back: {}", e.getMessage());
            return Response.from(fallbackScores(segments.size()));
        }
    }

    /** Parse DashScope rerank response into scores aligned with input order. */
    private List<Double> parseScores(String responseBody, int total) throws Exception {
        Map<String, Object> root = mapper.readValue(responseBody,
                new TypeReference<Map<String, Object>>() {});
        Map<String, Object> output = (Map<String, Object>) root.get("output");
        if (output == null) return fallbackScores(total);

        List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");
        if (results == null || results.isEmpty()) return fallbackScores(total);

        // Build full score array, defaulting unranked docs to 0
        double[] scores = new double[total];
        for (Map<String, Object> r : results) {
            int idx = ((Number) r.get("index")).intValue();
            double score = ((Number) r.get("relevance_score")).doubleValue();
            if (idx >= 0 && idx < total) {
                scores[idx] = score;
            }
        }
        List<Double> list = new ArrayList<>(total);
        for (double s : scores) list.add(s);
        return list;
    }

    private List<Double> fallbackScores(int total) {
        List<Double> list = new ArrayList<>(total);
        for (int i = 0; i < total; i++) list.add(0.0);
        return list;
    }
}
