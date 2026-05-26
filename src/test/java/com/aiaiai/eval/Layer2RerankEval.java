package com.aiaiai.eval;

import com.aiaiai.reranker.DashScopeReranker;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
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

    record RowResult(String id, int denseRank, int rerankRank,
                      boolean denseHit5, boolean rerankHit5,
                      double denseMrr, double rerankMrr) {}

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

                row = new RowResult(eq.id(), denseRank, rerankRank,
                        denseHit5, rerankHit5, denseMrr, rerankMrr);
            } catch (Exception e) {
                row = new RowResult(eq.id(), -1, -1, false, false, 0, 0);
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
        int improved = 0, degraded = 0, same = 0;

        for (var r : results) {
            if (r.denseHit5) denseHit5Sum++;
            if (r.rerankHit5) rerankHit5Sum++;
            denseMrrSum += r.denseMrr;
            rerankMrrSum += r.rerankMrr;
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
}
