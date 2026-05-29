package com.aiaiai.routing;

/**
 * Layer 0 intent categories. Each carries its retrieval pipeline config.
 */
public enum QueryIntent {

    CHAT(false, 0, false, 0),
    BOUNDARY(false, 0, false, 0),

    METHOD_NAME(true, 5, false, 0),
    SPECIFIC(false, 5, false, 0),
    CONCEPT(true, 20, false, 0),
    BROAD(true, 20, false, 0),
    VAGUE_REFERENCE(false, 20, true, 5),
    TYPO_VARIANT(true, 20, true, 5),
    ORAL_EMOTIONAL(true, 20, true, 5),
    MIXED_INTENT(true, 10, false, 0),

    FALLBACK(true, 20, false, 0);

    private final PipelineConfig config;

    QueryIntent(boolean rewrite, int topK, boolean rerank, int finalTopK) {
        this.config = new PipelineConfig(rewrite, topK, rerank, finalTopK);
    }

    public PipelineConfig config() { return config; }

    public static QueryIntent fromLabel(String label) {
        if (label == null) return FALLBACK;
        try {
            return valueOf(label.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            return FALLBACK;
        }
    }

    public record PipelineConfig(boolean rewrite, int topK, boolean rerank, int finalTopK) {}
}
