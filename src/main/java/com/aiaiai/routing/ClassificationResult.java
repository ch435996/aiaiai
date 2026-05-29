package com.aiaiai.routing;

import java.util.Map;

public record ClassificationResult(
        QueryIntent intent,
        double confidence,
        Map<String, Double> contributors,
        String source) {

    public static final ClassificationResult FALLBACK =
            new ClassificationResult(QueryIntent.FALLBACK, 0.3, Map.of(), "DEFAULT");
}
