package com.aiaiai.eval;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeServerlessIndexConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

/**
 * Step 1: Enumerate all unique paper titles in knowledge_v2.
 * Uses broad-coverage queries to surface as many distinct papers as possible.
 */
public class PaperTitleProbe {

    // Broad queries designed to hit different subsets of the knowledge base
    private static final String[] PROBE_QUERIES = {
            "point cloud completion",
            "3D reconstruction shape completion",
            "point cloud upsampling",
            "diffusion model point cloud",
            "transformer point cloud",
            "coarse-to-fine point cloud",
            "point cloud evaluation metrics",
            "自监督点云补全",
            "点云配准",
    };

    @Test
    void enumeratePapers() {
        String apiKey = env("EMBEDDING_V2_API_KEY", env("EMBEDDING_API_KEY", ""));
        String baseUrl = env("EMBEDDING_V2_BASE_URL",
                env("EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        String modelName = env("EMBEDDING_V2_MODEL", "text-embedding-v4");
        int dims = Integer.parseInt(env("EMBEDDING_V2_DIMENSIONS", "1024"));
        String pineKey = env("PINECONE_API_KEY", "");
        String pineIndex = env("PINECONE_INDEX", "aiaiai-knowledge");

        if (apiKey.isBlank() || pineKey.isBlank()) {
            System.out.println("Missing API keys. Set EMBEDDING_V2_API_KEY and PINECONE_API_KEY.");
            return;
        }

        EmbeddingModel embModel = OpenAiEmbeddingModel.builder()
                .apiKey(apiKey).baseUrl(baseUrl).modelName(modelName)
                .dimensions(dims).maxSegmentsPerBatch(10)
                .timeout(Duration.ofSeconds(60)).build();

        PineconeEmbeddingStore store = PineconeEmbeddingStore.builder()
                .apiKey(pineKey).index(pineIndex).nameSpace("knowledge_v2")
                .createIndex(PineconeServerlessIndexConfig.builder()
                        .cloud("AWS").region("us-east-1").dimension(dims).build())
                .build();

        // Collect all unique (title, section) pairs
        Set<String> allTitles = new LinkedHashSet<>();
        Map<String, Integer> titleCount = new LinkedHashMap<>();

        for (String query : PROBE_QUERIES) {
            try {
                Embedding emb = embModel.embed(query).content();
                EmbeddingSearchResult<TextSegment> result = store.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(emb).maxResults(20).minScore(0.0).build());
                for (EmbeddingMatch<TextSegment> m : result.matches()) {
                    var meta = m.embedded().metadata();
                    String title = meta != null ? meta.getString("title") : null;
                    if (title == null || title.isBlank()) title = "[no title]";
                    else title = title.replace(".pdf", "").strip();
                    allTitles.add(title);
                    titleCount.merge(title, 1, Integer::sum);
                }
            } catch (Exception e) {
                System.out.println("Probe '" + query + "' failed: " + e.getMessage());
            }
        }

        System.out.println("=== All Unique Paper Titles in knowledge_v2 ===");
        System.out.printf("Total unique titles found: %d%n%n", allTitles.size());

        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(titleCount.entrySet());
        sorted.sort((a, b) -> b.getValue().compareTo(a.getValue()));

        int i = 1;
        for (var entry : sorted) {
            System.out.printf("%3d. [hit:%2d] %s%n", i++, entry.getValue(), entry.getKey());
        }

        // Also print a copy-pasteable list for ground truth setup
        System.out.println("\n=== Copy-paste for ground truth mapping ===");
        for (String title : allTitles) {
            System.out.printf("  \"%s\",%n", title);
        }
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
