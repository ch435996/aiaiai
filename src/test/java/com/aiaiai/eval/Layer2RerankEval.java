package com.aiaiai.eval;

import com.aiaiai.reranker.DashScopeReranker;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeServerlessIndexConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

/**
 * Layer 2: Reranker Evaluation — Cross-Encoder re-ranking impact.
 *
 * Pipeline:
 *   1. Dense retrieval: top-20 from Pinecone (Layer 1 baseline)
 *   2. Reranker: re-score via Cross-Encoder (ScoringModel.scoreAll)
 *   3. Re-rank by new score, measure MRR@5 delta vs dense-only
 *   4. Analyze reranker score distribution to recalibrate confidence thresholds
 *
 * Key questions:
 *   - Can the reranker pull correct chunks from the pool into top-5?
 *   - What does the reranker score distribution look like?
 *   - What should the new HIGH/MEDIUM/LOW thresholds be?
 */
public class Layer2RerankEval {

    private static final String REWRITER_PROMPT = """
            你是三维重建/点云补全领域的查询改写器。将用户的口语化检索查询改写为高质量的向量检索查询。

            改写规则：
            1. 将中文昵称/缩写扩展为完整英文方法名或论文名（如"雪花网络"→"SnowflakeNet"，"点云补全"→"point cloud completion"）
            2. 去除口语化填充词（那个、这个、一下、帮我查查等）、疑问标记（是什么、怎么样等）、礼貌用语
            3. 保留核心概念，添加英文技术关键词以提高检索召回率
            4. 如果查询本身已经是规范的技术术语，保持原样
            5. 意图纠偏——按颗粒度动态扩展：
               a) 先判断查询中是否包含【具体方法名/论文名实体】（如 SnowflakeNet、PoinTr、PCN、FoldingNet、
                  Point-MAE 等专有名词）。实体包括：完整英文方法名、中文昵称/缩写（雪花网络→SnowflakeNet）、
                  论文简称。
               b) 若查询包含具体实体 AND 查询目标为该实体的微观细节（模块结构、输入维度、损失函数、
                  与另一实体的精细对比等）→ 严禁追加任何 overview/survey/comparison 等泛化词，
                  仅做术语对齐和同义词替换。
               c) 若查询不包含具体实体（如"点云补全有哪些方法"、"自监督学习在补全中的应用"）
                  OR 查询虽提及实体但目标是宏观理解（如"SnowflakeNet的整体思想是什么"）→ 可追加
                  "overview survey comparison" 以扩大召回。

            只输出改写后的查询词，不要任何解释。""";

    record RowResult(String id, int denseRank, int rerankRank,
                      boolean denseHit5, boolean rerankHit5,
                      double denseMrr, double rerankMrr,
                      int denseGtIn3, int denseGtIn5,
                      int rerankGtIn3, int rerankGtIn5) {}

    record RewRowResult(String id, String rewritten, int denseRank, int rerankRank,
                         boolean denseHit5, boolean rerankHit5,
                         double denseMrr, double rerankMrr,
                         int denseGtIn3, int denseGtIn5,
                         int rerankGtIn3, int rerankGtIn5) {}

    // Collect all reranker scores for distribution analysis
    private final List<Double> allRerankScores = new ArrayList<>();

    @Test
    void evaluate() {
        String embKey = env("EMBEDDING_V2_API_KEY", env("EMBEDDING_API_KEY", ""));
        String embUrl = env("EMBEDDING_V2_BASE_URL",
                env("EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        String embModel = env("EMBEDDING_V2_MODEL", "text-embedding-v4");
        int dims = Integer.parseInt(env("EMBEDDING_V2_DIMENSIONS", "1024"));

        String pineKey = env("PINECONE_API_KEY", "");
        String pineIndex = env("PINECONE_INDEX", "aiaiai-knowledge");

        String rerankKey = env("RERANKER_API_KEY",
                env("EMBEDDING_V2_API_KEY", env("EMBEDDING_API_KEY", "")));
        String rerankBase = env("RERANKER_BASE_URL", "https://dashscope.aliyuncs.com");
        String rerankModel = env("RERANKER_MODEL", "gte-rerank");

        if (embKey.isBlank() || pineKey.isBlank() || rerankKey.isBlank()) {
            System.out.println("Missing API keys.");
            return;
        }

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(embKey).baseUrl(embUrl).modelName(embModel)
                .dimensions(dims).maxSegmentsPerBatch(10)
                .timeout(Duration.ofSeconds(60)).build();

        PineconeEmbeddingStore store = PineconeEmbeddingStore.builder()
                .apiKey(pineKey).index(pineIndex).nameSpace("knowledge_v2")
                .createIndex(PineconeServerlessIndexConfig.builder()
                        .cloud("AWS").region("us-east-1").dimension(dims).build())
                .build();

        ScoringModel reranker = new DashScopeReranker(rerankBase, rerankKey, rerankModel);

        List<EvalQueries.EvalQuery> allQueries = EvalQueries.all();

        System.out.println("=== Layer 2: Reranker Evaluation ===");
        System.out.printf("Embedding: %s | Reranker: %s | Pipeline: top-20 → rerank → top-5%n%n",
                embModel, rerankModel);

        List<RowResult> results = new ArrayList<>();
        Map<String, String> queryMap = new LinkedHashMap<>(); // id → rewritten query

        for (var eq : allQueries) {
            if (eq.groundTruth() == null) continue; // skip boundary
            queryMap.put(eq.id(), eq.query());

            RowResult row;
            try {
                Embedding emb = embeddingModel.embed(eq.query()).content();
                var searchResult = store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(emb).maxResults(20).minScore(0.0).build());
                List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

                int denseRank = firstHitRank(matches, eq.groundTruth());
                double denseMrr = denseRank > 0 ? 1.0 / denseRank : 0;
                boolean denseHit5 = denseRank > 0 && denseRank <= 5;

                // Reranker: TextSegment list for ScoringModel
                List<TextSegment> segments = new ArrayList<>();
                for (var m : matches) {
                    segments.add(m.embedded());
                }

                Response<List<Double>> scoreResponse = reranker.scoreAll(segments, eq.query());
                List<Double> scores = scoreResponse.content();

                // Collect all non-zero scores for distribution analysis
                for (double s : scores) {
                    if (s > 0) allRerankScores.add(s);
                }

                // Sort segments by reranker score descending
                List<Integer> indices = new ArrayList<>();
                for (int i = 0; i < scores.size(); i++) indices.add(i);
                indices.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

                int rerankRank = -1;
                String[] gt = eq.groundTruth();
                for (int i = 0; i < Math.min(5, indices.size()); i++) {
                    var meta = matches.get(indices.get(i)).embedded().metadata();
                    String title = meta != null ? meta.getString("title") : null;
                    if (title != null && matchesGt(title, gt)) {
                        rerankRank = i + 1;
                        break;
                    }
                }
                double rerankMrr = rerankRank > 0 ? 1.0 / rerankRank : 0;
                boolean rerankHit5 = rerankRank > 0;

                // Precision: count GT hits in top-K
                int denseGtIn3 = countGtInTopK(matches, gt, 3, null);
                int denseGtIn5 = countGtInTopK(matches, gt, 5, null);
                int rerankGtIn3 = countGtInTopK(matches, gt, 3, indices);
                int rerankGtIn5 = countGtInTopK(matches, gt, 5, indices);

                row = new RowResult(eq.id(), denseRank, rerankRank,
                        denseHit5, rerankHit5, denseMrr, rerankMrr,
                        denseGtIn3, denseGtIn5, rerankGtIn3, rerankGtIn5);
            } catch (Exception e) {
                row = new RowResult(eq.id(), -1, -1, false, false, 0, 0, 0, 0, 0, 0);
            }
            results.add(row);
        }

        // ── Print table ──
        System.out.printf("%-5s %-40s %8s %8s %8s %8s %8s%n",
                "ID", "QUERY", "DENSE#", "RERANK#", "D_MRR", "R_MRR", "DELTA");
        System.out.println("-".repeat(90));

        // Build a lookup for query text
        Map<String, String> idToQuery = new LinkedHashMap<>();
        for (var eq : allQueries) {
            if (eq.groundTruth() != null) idToQuery.put(eq.id(), eq.query());
        }

        int i = 0;
        for (var r : results) {
            String id = new ArrayList<>(idToQuery.keySet()).get(i);
            String q = idToQuery.get(id);
            double delta = r.rerankMrr - r.denseMrr;
            String flag = delta > 0.001 ? "+" : delta < -0.001 ? "-" : " ";
            System.out.printf("%-5s %-40s %8s %8s %8s %8s %s%n",
                    id, trunc(q, 40),
                    r.denseRank > 0 ? String.valueOf(r.denseRank) : "-",
                    r.rerankRank > 0 ? String.valueOf(r.rerankRank) : "-",
                    fmt(r.denseMrr), fmt(r.rerankMrr),
                    flag + (Math.abs(delta) < 0.001 ? "" : fmt(Math.abs(delta))));
            i++;
        }

        // ── Aggregate ──
        int total = results.size();
        double denseHit5Sum = 0, rerankHit5Sum = 0;
        double denseMrrSum = 0, rerankMrrSum = 0;
        double densePrec3Sum = 0, densePrec5Sum = 0;
        double rerankPrec3Sum = 0, rerankPrec5Sum = 0;
        int improved = 0, degraded = 0, same = 0;

        for (var r : results) {
            if (r.denseHit5) denseHit5Sum++;
            if (r.rerankHit5) rerankHit5Sum++;
            denseMrrSum += r.denseMrr;
            rerankMrrSum += r.rerankMrr;
            densePrec3Sum += r.denseGtIn3 / 3.0;
            densePrec5Sum += r.denseGtIn5 / 5.0;
            rerankPrec3Sum += r.rerankGtIn3 / 3.0;
            rerankPrec5Sum += r.rerankGtIn5 / 5.0;
            if (r.rerankMrr > r.denseMrr + 0.001) improved++;
            else if (r.rerankMrr < r.denseMrr - 0.001) degraded++;
            else same++;
        }

        System.out.println("\n=== Aggregate (" + total + " in-scope queries) ===");
        System.out.printf("%-18s %10s %10s %10s%n", "METRIC", "DENSE", "RERANKED", "DELTA");
        System.out.println("-".repeat(52));
        System.out.printf("%-18s %9.1f%% %9.1f%% %+9.1f%%%n",
                "Recall@5", denseHit5Sum / total * 100,
                rerankHit5Sum / total * 100,
                (rerankHit5Sum - denseHit5Sum) / total * 100);
        System.out.printf("%-18s %9.1f%% %9.1f%% %+9.1f%%%n",
                "Precision@3", densePrec3Sum / total * 100,
                rerankPrec3Sum / total * 100,
                (rerankPrec3Sum - densePrec3Sum) / total * 100);
        System.out.printf("%-18s %9.1f%% %9.1f%% %+9.1f%%%n",
                "Precision@5", densePrec5Sum / total * 100,
                rerankPrec5Sum / total * 100,
                (rerankPrec5Sum - densePrec5Sum) / total * 100);
        System.out.printf("%-18s %10s %10s %10s%n",
                "MRR@5", fmt(denseMrrSum / total), fmt(rerankMrrSum / total),
                fmtDelta(rerankMrrSum - denseMrrSum, total));
        System.out.printf("Queries: %d improved, %d degraded, %d unchanged%n",
                improved, degraded, same);

        // ── Per-category MRR ──
        System.out.println("\n=== Per-Category MRR ===");
        System.out.printf("%-18s %6s %8s %8s %8s%n",
                "CATEGORY", "N", "D_MRR", "R_MRR", "DELTA");
        System.out.println("-".repeat(52));
        for (var cat : EvalQueries.Category.values()) {
            if (cat == EvalQueries.Category.BOUNDARY) continue;
            double dMrr = 0, rMrr = 0;
            int n = 0;
            for (int j = 0; j < allQueries.size(); j++) {
                if (allQueries.get(j).category() == cat && allQueries.get(j).groundTruth() != null) {
                    // Find corresponding result
                    int resultIdx = findResultIdx(allQueries, j, results);
                    if (resultIdx >= 0) {
                        dMrr += results.get(resultIdx).denseMrr;
                        rMrr += results.get(resultIdx).rerankMrr;
                        n++;
                    }
                }
            }
            if (n == 0) continue;
            dMrr /= n; rMrr /= n;
            System.out.printf("%-18s %6d %8s %8s %8s%n",
                    "[" + cat + "]", n, fmt(dMrr), fmt(rMrr), fmtDeltaVal(rMrr - dMrr));
        }

        // ── Score distribution analysis (key for recalibrating thresholds) ──
        printScoreDistribution();

        // ── Threshold recommendation ──
        printThresholdRecommendation();

        System.out.println("\n=== Key Insight ===");
        double denseR20 = 0;
        for (int j = 0; j < results.size(); j++) {
            var r = results.get(j);
            if (r.denseRank > 0 && r.denseRank <= 20) denseR20++;
        }
        System.out.printf("Dense Recall@20: %.1f%% — pool coverage (Layer 1 baseline)%n",
                denseR20 / total * 100);
        if (improved > degraded) {
            System.out.printf("Reranker improves %d/%d queries — "
                    + "Cross-Encoder pulls correct answers upward.%n", improved, total);
        }
    }

    private int findResultIdx(List<EvalQueries.EvalQuery> all, int queryIdx,
                               List<RowResult> results) {
        int resultIdx = 0;
        for (int j = 0; j < queryIdx; j++) {
            if (all.get(j).groundTruth() != null) resultIdx++;
        }
        return resultIdx < results.size() ? resultIdx : -1;
    }

    private void printScoreDistribution() {
        if (allRerankScores.isEmpty()) {
            System.out.println("\n=== Score Distribution: NO DATA ===");
            return;
        }
        List<Double> sorted = new ArrayList<>(allRerankScores);
        Collections.sort(sorted);

        double min = sorted.get(0);
        double max = sorted.get(sorted.size() - 1);
        double mean = sorted.stream().mapToDouble(d -> d).average().orElse(0);
        double median = percentile(sorted, 0.5);
        double p25 = percentile(sorted, 0.25);
        double p75 = percentile(sorted, 0.75);
        double p90 = percentile(sorted, 0.90);
        double stddev = Math.sqrt(sorted.stream()
                .mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0));

        System.out.println("\n=== Reranker Score Distribution (" + sorted.size() + " scores) ===");
        System.out.printf("Min:    %.4f%n", min);
        System.out.printf("Max:    %.4f%n", max);
        System.out.printf("Mean:   %.4f%n", mean);
        System.out.printf("StdDev: %.4f%n", stddev);
        System.out.printf("Median: %.4f%n", median);
        System.out.printf("P25:    %.4f%n", p25);
        System.out.printf("P75:    %.4f%n", p75);
        System.out.printf("P90:    %.4f%n", p90);
        System.out.printf("Spread: %.4f (max-min)%n", max - min);

        // Compare with cosine similarity distribution
        System.out.println("\nReference — Dense cosine scores (from Layer 1):");
        System.out.println("  Range: 0.75 ~ 0.91, typical intra-query spread: ~0.02");
        System.out.printf("  Reranker spread is %.0fx wider → much better ranking resolution%n",
                (max - min) / 0.02);
    }

    private void printThresholdRecommendation() {
        if (allRerankScores.isEmpty()) return;

        List<Double> sorted = new ArrayList<>(allRerankScores);
        Collections.sort(sorted);

        double highThreshold = percentile(sorted, 0.75);
        double medThreshold = percentile(sorted, 0.40);

        System.out.println("\n=== Recommended Reranker Confidence Thresholds ===");
        System.out.println("These replace the cosine-similarity thresholds (0.85 / 0.70).");
        System.out.printf("HIGH   (>= %.3f): top 25%% of reranker scores%n", highThreshold);
        System.out.printf("MEDIUM (>= %.3f): top 60%% of reranker scores%n", medThreshold);
        System.out.printf("LOW    (<  %.3f): bottom 40%% — cross-check with other sources%n",
                medThreshold);
        System.out.println();
        System.out.println("To apply: set in application.yml:");
        System.out.printf("  aiaiai.reranker.high-confidence: %.3f%n", highThreshold);
        System.out.printf("  aiaiai.reranker.medium-confidence: %.3f%n", medThreshold);
        System.out.println();
        System.out.println("Note: these are initial recommendations based on one eval run.");
        System.out.println("Recalibrate after adding new papers or switching reranker models.");
    }

    // ── Helpers ──

    private int firstHitRank(List<EmbeddingMatch<TextSegment>> matches, String[] gt) {
        for (int i = 0; i < matches.size(); i++) {
            var meta = matches.get(i).embedded().metadata();
            String title = meta != null ? meta.getString("title") : null;
            if (title != null && matchesGt(title, gt)) return i + 1;
        }
        return -1;
    }

    /** Count how many of the top-K results (after optional rerank ordering) match any GT title. */
    private int countGtInTopK(List<EmbeddingMatch<TextSegment>> matches, String[] gt, int k,
                               List<Integer> rerankOrder) {
        int count = 0;
        int limit = Math.min(k, matches.size());
        for (int i = 0; i < limit; i++) {
            int idx = rerankOrder != null ? rerankOrder.get(i) : i;
            var meta = matches.get(idx).embedded().metadata();
            String title = meta != null ? meta.getString("title") : null;
            if (title != null && matchesGt(title, gt)) count++;
        }
        return count;
    }

    private boolean matchesGt(String title, String[] gt) {
        String lower = title.toLowerCase();
        for (String g : gt) {
            if (lower.contains(g.toLowerCase())) return true;
        }
        return false;
    }

    private static double percentile(List<Double> sorted, double p) {
        int idx = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(idx, sorted.size() - 1)));
    }

    private static String trunc(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static String fmt(double v) {
        return String.format("%.3f", v);
    }

    private static String fmtDelta(double delta, int total) {
        double val = total > 0 ? delta / total : 0;
        return (val >= 0 ? "+" : "") + String.format("%.3f", val);
    }

    private static String fmtDeltaVal(double delta) {
        return (delta >= 0 ? "+" : "") + String.format("%.3f", delta);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private String rewrite(ChatModel model, String query) {
        try {
            var resp = model.chat(SystemMessage.from(REWRITER_PROMPT), UserMessage.from(query));
            String r = resp.aiMessage().text();
            return (r != null && !r.isBlank()) ? r.strip() : query;
        } catch (Exception e) {
            return query;
        }
    }

    /**
     * Layer 2 eval with QueryRewriter: rewrite → embed → top-20 → rerank → top-5.
     * Measures reranker impact when the retrieval query has been rewritten by the LLM.
     */
    @Test
    void evaluateWithRewriter() {
        String embKey = env("EMBEDDING_V2_API_KEY", env("EMBEDDING_API_KEY", ""));
        String embUrl = env("EMBEDDING_V2_BASE_URL",
                env("EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        String embModel = env("EMBEDDING_V2_MODEL", "text-embedding-v4");
        int dims = Integer.parseInt(env("EMBEDDING_V2_DIMENSIONS", "1024"));

        String llmKey = env("DEEPSEEK_API_KEY", "");
        String llmUrl = env("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String llmModel = env("DEEPSEEK_MODEL", "deepseek-chat");

        String pineKey = env("PINECONE_API_KEY", "");
        String pineIndex = env("PINECONE_INDEX", "aiaiai-knowledge");

        String rerankKey = env("RERANKER_API_KEY",
                env("EMBEDDING_V2_API_KEY", env("EMBEDDING_API_KEY", "")));
        String rerankBase = env("RERANKER_BASE_URL", "https://dashscope.aliyuncs.com");
        String rerankModel = env("RERANKER_MODEL", "gte-rerank");

        if (embKey.isBlank() || pineKey.isBlank() || rerankKey.isBlank() || llmKey.isBlank()) {
            System.out.println("Missing API keys.");
            return;
        }

        EmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .apiKey(embKey).baseUrl(embUrl).modelName(embModel)
                .dimensions(dims).maxSegmentsPerBatch(10)
                .timeout(Duration.ofSeconds(60)).build();

        PineconeEmbeddingStore store = PineconeEmbeddingStore.builder()
                .apiKey(pineKey).index(pineIndex).nameSpace("knowledge_v2")
                .createIndex(PineconeServerlessIndexConfig.builder()
                        .cloud("AWS").region("us-east-1").dimension(dims).build())
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(llmKey).baseUrl(llmUrl).modelName(llmModel)
                .temperature(0.0).maxTokens(256)
                .timeout(Duration.ofSeconds(30)).build();

        ScoringModel reranker = new DashScopeReranker(rerankBase, rerankKey, rerankModel);

        List<EvalQueries.EvalQuery> allQueries = EvalQueries.all();
        List<Double> rewAllScores = new ArrayList<>();

        System.out.println("=== Layer 2: Reranker Evaluation (QueryRewriter) ===");
        System.out.printf("Embedding: %s | Reranker: %s | Rewriter: %s | Pipeline: rewrite → embed → top-20 → rerank → top-5%n%n",
                embModel, rerankModel, llmModel);

        List<RewRowResult> results = new ArrayList<>();

        for (var eq : allQueries) {
            if (eq.groundTruth() == null) continue;

            String rewritten = eq.query();
            RewRowResult row;
            try {
                rewritten = rewrite(chatModel, eq.query());
                System.out.printf("[%s] raw: '%s' → rewritten: '%s'%n", eq.id(), eq.query(), rewritten);

                Embedding emb = embeddingModel.embed(rewritten).content();
                var searchResult = store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(emb).maxResults(20).minScore(0.0).build());
                List<EmbeddingMatch<TextSegment>> matches = searchResult.matches();

                int denseRank = firstHitRank(matches, eq.groundTruth());
                double denseMrr = denseRank > 0 ? 1.0 / denseRank : 0;
                boolean denseHit5 = denseRank > 0 && denseRank <= 5;

                List<TextSegment> segments = new ArrayList<>();
                for (var m : matches) {
                    segments.add(m.embedded());
                }

                Response<List<Double>> scoreResponse = reranker.scoreAll(segments, rewritten);
                List<Double> scores = scoreResponse.content();

                for (double s : scores) {
                    if (s > 0) rewAllScores.add(s);
                }

                List<Integer> indices = new ArrayList<>();
                for (int i = 0; i < scores.size(); i++) indices.add(i);
                indices.sort((a, b) -> Double.compare(scores.get(b), scores.get(a)));

                int rerankRank = -1;
                String[] gt = eq.groundTruth();
                for (int i = 0; i < Math.min(5, indices.size()); i++) {
                    var meta = matches.get(indices.get(i)).embedded().metadata();
                    String title = meta != null ? meta.getString("title") : null;
                    if (title != null && matchesGt(title, gt)) {
                        rerankRank = i + 1;
                        break;
                    }
                }
                double rerankMrr = rerankRank > 0 ? 1.0 / rerankRank : 0;
                boolean rerankHit5 = rerankRank > 0;

                int denseGtIn3 = countGtInTopK(matches, gt, 3, null);
                int denseGtIn5 = countGtInTopK(matches, gt, 5, null);
                int rerankGtIn3 = countGtInTopK(matches, gt, 3, indices);
                int rerankGtIn5 = countGtInTopK(matches, gt, 5, indices);

                row = new RewRowResult(eq.id(), rewritten, denseRank, rerankRank,
                        denseHit5, rerankHit5, denseMrr, rerankMrr,
                        denseGtIn3, denseGtIn5, rerankGtIn3, rerankGtIn5);
            } catch (Exception e) {
                row = new RewRowResult(eq.id(), rewritten, -1, -1, false, false, 0, 0, 0, 0, 0, 0);
            }
            results.add(row);
        }

        // ── Print table ──
        System.out.printf("%n%-5s %-38s %-45s %8s %8s %8s %8s %8s%n",
                "ID", "RAW", "REWRITTEN", "DENSE#", "RERANK#", "D_MRR", "R_MRR", "DELTA");
        System.out.println("-".repeat(135));

        for (var r : results) {
            double delta = r.rerankMrr - r.denseMrr;
            String flag = delta > 0.001 ? "+" : delta < -0.001 ? "-" : " ";
            System.out.printf("%-5s %-38s %-45s %8s %8s %8s %8s %s%n",
                    r.id, trunc(r.rewritten.length() > 38 ? r.rewritten.substring(0, 38) : r.rewritten, 38),
                    trunc(r.rewritten, 45),
                    r.denseRank > 0 ? String.valueOf(r.denseRank) : "-",
                    r.rerankRank > 0 ? String.valueOf(r.rerankRank) : "-",
                    fmt(r.denseMrr), fmt(r.rerankMrr),
                    flag + (Math.abs(delta) < 0.001 ? "" : fmt(Math.abs(delta))));
        }

        // ── Aggregate ──
        int total = results.size();
        double denseHit5Sum = 0, rerankHit5Sum = 0;
        double denseMrrSum = 0, rerankMrrSum = 0;
        double densePrec3Sum = 0, densePrec5Sum = 0;
        double rerankPrec3Sum = 0, rerankPrec5Sum = 0;
        int improved = 0, degraded = 0, same = 0;

        for (var r : results) {
            if (r.denseHit5) denseHit5Sum++;
            if (r.rerankHit5) rerankHit5Sum++;
            denseMrrSum += r.denseMrr;
            rerankMrrSum += r.rerankMrr;
            densePrec3Sum += r.denseGtIn3 / 3.0;
            densePrec5Sum += r.denseGtIn5 / 5.0;
            rerankPrec3Sum += r.rerankGtIn3 / 3.0;
            rerankPrec5Sum += r.rerankGtIn5 / 5.0;
            if (r.rerankMrr > r.denseMrr + 0.001) improved++;
            else if (r.rerankMrr < r.denseMrr - 0.001) degraded++;
            else same++;
        }

        System.out.println("\n=== Aggregate (" + total + " in-scope queries, with QueryRewriter) ===");
        System.out.printf("%-18s %10s %10s %10s%n", "METRIC", "DENSE", "RERANKED", "DELTA");
        System.out.println("-".repeat(52));
        System.out.printf("%-18s %9.1f%% %9.1f%% %+9.1f%%%n",
                "Recall@5", denseHit5Sum / total * 100,
                rerankHit5Sum / total * 100,
                (rerankHit5Sum - denseHit5Sum) / total * 100);
        System.out.printf("%-18s %9.1f%% %9.1f%% %+9.1f%%%n",
                "Precision@3", densePrec3Sum / total * 100,
                rerankPrec3Sum / total * 100,
                (rerankPrec3Sum - densePrec3Sum) / total * 100);
        System.out.printf("%-18s %9.1f%% %9.1f%% %+9.1f%%%n",
                "Precision@5", densePrec5Sum / total * 100,
                rerankPrec5Sum / total * 100,
                (rerankPrec5Sum - densePrec5Sum) / total * 100);
        System.out.printf("%-18s %10s %10s %10s%n",
                "MRR@5", fmt(denseMrrSum / total), fmt(rerankMrrSum / total),
                fmtDelta(rerankMrrSum - denseMrrSum, total));
        System.out.printf("Queries: %d improved, %d degraded, %d unchanged%n",
                improved, degraded, same);

        // ── Per-category MRR ──
        System.out.println("\n=== Per-Category MRR (with QueryRewriter) ===");
        System.out.printf("%-18s %6s %8s %8s %8s%n",
                "CATEGORY", "N", "D_MRR", "R_MRR", "DELTA");
        System.out.println("-".repeat(52));
        for (var cat : EvalQueries.Category.values()) {
            if (cat == EvalQueries.Category.BOUNDARY) continue;
            double dMrr = 0, rMrr = 0;
            int n = 0;
            for (int j = 0; j < allQueries.size(); j++) {
                if (allQueries.get(j).category() == cat && allQueries.get(j).groundTruth() != null) {
                    int resultIdx = findRewResultIdx(allQueries, j, results);
                    if (resultIdx >= 0) {
                        dMrr += results.get(resultIdx).denseMrr;
                        rMrr += results.get(resultIdx).rerankMrr;
                        n++;
                    }
                }
            }
            if (n == 0) continue;
            dMrr /= n; rMrr /= n;
            System.out.printf("%-18s %6d %8s %8s %8s%n",
                    "[" + cat + "]", n, fmt(dMrr), fmt(rMrr), fmtDeltaVal(rMrr - dMrr));
        }

        // ── Score distribution ──
        printRewScoreDistribution(rewAllScores);

        // ── Improved / degraded highlights ──
        System.out.println("\n=== Improvement Highlights (with Rewriter) ===");
        for (var r : results) {
            double delta = r.rerankMrr - r.denseMrr;
            if (delta > 0.1) {
                System.out.printf("+ %s: Dense#%d → Rerank#%d  MRR %.3f → %.3f  [%s]%n",
                        r.id, r.denseRank, r.rerankRank, r.denseMrr, r.rerankMrr,
                        trunc(r.rewritten, 60));
            }
        }

        System.out.println("\n=== Degradation Highlights (with Rewriter) ===");
        for (var r : results) {
            double delta = r.rerankMrr - r.denseMrr;
            if (delta < -0.1) {
                System.out.printf("- %s: Dense#%d → Rerank#%d  MRR %.3f → %.3f  [%s]%n",
                        r.id, r.denseRank, r.rerankRank, r.denseMrr, r.rerankMrr,
                        trunc(r.rewritten, 60));
            }
        }

        System.out.println("\nDone.");
    }

    private int findRewResultIdx(List<EvalQueries.EvalQuery> all, int queryIdx,
                                  List<RewRowResult> results) {
        int resultIdx = 0;
        for (int j = 0; j < queryIdx; j++) {
            if (all.get(j).groundTruth() != null) resultIdx++;
        }
        return resultIdx < results.size() ? resultIdx : -1;
    }

    private void printRewScoreDistribution(List<Double> scores) {
        if (scores.isEmpty()) {
            System.out.println("\n=== Reranker Score Distribution (Rewriter): NO DATA ===");
            return;
        }
        List<Double> sorted = new ArrayList<>(scores);
        Collections.sort(sorted);

        double min = sorted.get(0);
        double max = sorted.get(sorted.size() - 1);
        double mean = sorted.stream().mapToDouble(d -> d).average().orElse(0);
        double median = percentile(sorted, 0.5);
        double p25 = percentile(sorted, 0.25);
        double p75 = percentile(sorted, 0.75);
        double p90 = percentile(sorted, 0.90);
        double stddev = Math.sqrt(sorted.stream()
                .mapToDouble(d -> Math.pow(d - mean, 2)).average().orElse(0));

        System.out.println("\n=== Reranker Score Distribution (" + sorted.size() + " scores, with Rewriter) ===");
        System.out.printf("Min:    %.4f%n", min);
        System.out.printf("Max:    %.4f%n", max);
        System.out.printf("Mean:   %.4f%n", mean);
        System.out.printf("StdDev: %.4f%n", stddev);
        System.out.printf("Median: %.4f%n", median);
        System.out.printf("P25:    %.4f%n", p25);
        System.out.printf("P75:    %.4f%n", p75);
        System.out.printf("P90:    %.4f%n", p90);
        System.out.printf("Spread: %.4f (max-min)%n", max - min);
    }
}
