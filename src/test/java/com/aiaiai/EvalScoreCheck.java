package com.aiaiai;

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
import java.util.List;

public class EvalScoreCheck {

    private static final String[][] QUERIES = {
            {"H1", "SnowflakeNet 的核心思想"},
            {"H2", "PoinTr 的网络结构"},
            {"H3", "FoldingNet 的点云补全方法"},
            {"H4", "PCN 点云补全"},
            {"M1", "点云补全的损失函数有哪些"},
            {"M2", "Transformer 在点云补全中的应用"},
            {"M3", "coarse-to-fine 策略在点云补全中"},
            {"L1", "三维重建的评价指标"},
            {"L2", "点云上采样和补全有什么区别"},
            {"L3", "自监督学习在点云处理中的应用"},
            {"E1", "ICP 点云配准算法"},
            {"E2", "3D Gaussian Splatting"},
    };

    @Test
    void checkV4ScoreDistribution() {
        String embeddingApiKey = env("EMBEDDING_V2_API_KEY", env("EMBEDDING_API_KEY", ""));
        String embeddingBaseUrl = env("EMBEDDING_V2_BASE_URL",
                env("EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        String embeddingModelName = env("EMBEDDING_V2_MODEL", "text-embedding-v4");
        int dimensions = Integer.parseInt(env("EMBEDDING_V2_DIMENSIONS", "1024"));

        String pineconeApiKey = env("PINECONE_API_KEY", "");
        String pineconeIndex = env("PINECONE_INDEX", "aiaiai-knowledge");
        String namespace = "knowledge_v2";

        if (embeddingApiKey.isBlank() || pineconeApiKey.isBlank()) {
            System.err.println("Missing API keys. Set EMBEDDING_V2_API_KEY and PINECONE_API_KEY.");
            return;
        }

        System.out.println("=== V4 Embedding Score Distribution Check ===");
        System.out.printf("Embedding model: %s (dim=%d)%n", embeddingModelName, dimensions);
        System.out.printf("Pinecone: index=%s, namespace=%s%n%n", pineconeIndex, namespace);

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(embeddingApiKey)
                .baseUrl(embeddingBaseUrl)
                .modelName(embeddingModelName)
                .dimensions(dimensions)
                .maxSegmentsPerBatch(10)
                .timeout(Duration.ofSeconds(60))
                .build();

        PineconeEmbeddingStore store = PineconeEmbeddingStore.builder()
                .apiKey(pineconeApiKey)
                .index(pineconeIndex)
                .nameSpace(namespace)
                .createIndex(PineconeServerlessIndexConfig.builder()
                        .cloud("AWS")
                        .region("us-east-1")
                        .dimension(dimensions)
                        .build())
                .build();

        // First, probe what's actually in the v2 namespace
        System.out.println("--- Probing v2 namespace content ---");
        try {
            Embedding probeEmb = embeddingModel.embed("point cloud completion 3D reconstruction").content();
            EmbeddingSearchResult<TextSegment> probeResult = store.search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(probeEmb)
                            .maxResults(10)
                            .minScore(0.0)
                            .build());
            System.out.printf("Probe returned %d matches. Sample sources:%n", probeResult.matches().size());
            for (var m : probeResult.matches()) {
                var meta = m.embedded().metadata();
                String title = meta != null ? meta.getString("title") : null;
                String section = meta != null ? meta.getString("section") : null;
                if (title == null || title.isBlank()) title = meta != null ? meta.toMap().toString() : "—";
                else title = title.replace(".pdf", "");
                System.out.printf("  [%.4f] title=%s  section=%s%n", m.score(), title, section != null ? section : "—");
            }
        } catch (Exception e) {
            System.out.println("Probe failed: " + e.getMessage());
        }

        System.out.println("\n--- Score distribution ---");
        System.out.printf("%-6s %-22s %5s  %-8s  %s%n", "TIER", "QUERY", "RANK", "SCORE", "SPREAD");
        System.out.println("------------------------------------------------------------");

        int totalQueries = 0;
        double allMin = 1.0, allMax = 0.0;

        for (String[] q : QUERIES) {
            String tier = q[0];
            String query = q[1];
            totalQueries++;

            try {
                Embedding queryEmbedding = embeddingModel.embed(query).content();
                EmbeddingSearchResult<TextSegment> result = store.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(queryEmbedding)
                                .maxResults(5)
                                .minScore(0.0)
                                .build());

                List<EmbeddingMatch<TextSegment>> matches = result.matches();

                if (matches.isEmpty()) {
                    System.out.printf("%-6s %-22s %5s  %-8s  %s%n", tier, trunc(query, 22), "—", "NO_RES", "—");
                    continue;
                }

                double qMin = 1.0, qMax = 0.0;
                for (int i = 0; i < matches.size(); i++) {
                    double score = matches.get(i).score();
                    qMin = Math.min(qMin, score);
                    qMax = Math.max(qMax, score);
                    allMin = Math.min(allMin, score);
                    allMax = Math.max(allMax, score);
                    String label = tier + (i == 0 ? "" : " ");
                    String queryLabel = i == 0 ? trunc(query, 22) : "";
                    System.out.printf("%-6s %-22s %5d  %.4f%n", label, queryLabel, i + 1, score);
                }
                double intraSpread = qMax - qMin;
                System.out.printf("%-6s %-22s %5s  %-8s  %.4f%n", "", "", "", "", intraSpread);
            } catch (Exception e) {
                System.out.printf("%-6s %-22s %5s  %-8s  ERROR%n", tier, trunc(query, 22), "—", "—");
            }
        }

        System.out.println("\n=== Summary ===");
        System.out.printf("Total queries: %d%n", totalQueries);
        System.out.printf("Overall score range: %.4f ~ %.4f (span=%.4f)%n", allMin, allMax, allMax - allMin);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private static String trunc(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
}
