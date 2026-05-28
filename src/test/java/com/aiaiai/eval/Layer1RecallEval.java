package com.aiaiai.eval;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
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
 * Layer 1: Dense Retrieval Evaluation — 50-query ablation test.
 *
 * Metrics per query path (raw vs QueryRewriter-rewritten):
 *   Hit@K, CappedR@5, CappedR@20, MRR, Precision@5
 *
 * Scoring strategy per multiTarget flag:
 *   single-target → Hit@K (binary: found or not)
 *   multi-target  → Capped Recall@K (hits / min(|GT|, K))
 *
 * Boundary queries (out-of-scope): measured by top-1 score distribution
 * rather than recall, since no ground truth exists.
 */
public class Layer1RecallEval {

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

    record QResult(String query, String rewritten, boolean hit5, boolean hit20,
                   double mrr, int gtIn5, int gtIn20, double top1Score,
                   double cappedR5, double cappedR20) {}

    @Test
    void evaluate() {
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

        if (embKey.isBlank() || pineKey.isBlank() || llmKey.isBlank()) {
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

        List<EvalQueries.EvalQuery> allQueries = EvalQueries.all();

        // Run all queries through both paths
        List<QResult> rawResults = new ArrayList<>();
        List<QResult> rewResults = new ArrayList<>();
        List<String> rewrittenTexts = new ArrayList<>();

        for (var eq : allQueries) {
            // Raw path
            try {
                Embedding emb = embeddingModel.embed(eq.query()).content();
                var res = store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(emb).maxResults(20).minScore(0.0).build());
                rawResults.add(evaluate(eq, eq.query(), res.matches()));
            } catch (Exception e) {
                rawResults.add(new QResult(eq.query(), eq.query(), false, false, 0, 0, 0, 0, 0.0, 0.0));
            }

            // Rewritten path
            String rewritten = eq.query();
            try {
                rewritten = rewrite(chatModel, eq.query());
                Embedding emb = embeddingModel.embed(rewritten).content();
                var res = store.search(EmbeddingSearchRequest.builder()
                        .queryEmbedding(emb).maxResults(20).minScore(0.0).build());
                rewResults.add(evaluate(eq, rewritten, res.matches()));
            } catch (Exception e) {
                rewResults.add(new QResult(eq.query(), rewritten, false, false, 0, 0, 0, 0, 0.0, 0.0));
            }
            rewrittenTexts.add(rewritten);
        }

        // ── Print detailed table ──
        System.out.println("=== Layer 1: 50-Query Recall Evaluation ===");
        System.out.printf("Embedding: %s | LLM rewriter: %s | Papers: 20%n%n", embModel, llmModel);

        // In-scope detail
        System.out.println("── In-Scope Queries (42) ──");
        printInScopeTable(allQueries, rawResults, rewResults);

        // Boundary detail
        System.out.println("\n── Boundary Queries (8) ──");
        printBoundaryTable(allQueries, rawResults, rewResults);

        // ── Aggregate: in-scope ──
        System.out.println("\n=== In-Scope Aggregate (42 queries) ===");
        printAggregate(allQueries, rawResults, rewResults, false);

        // ── Per-category breakdown ──
        for (var cat : EvalQueries.Category.values()) {
            if (cat == EvalQueries.Category.BOUNDARY) continue;
            printCategoryAggregate(cat, allQueries, rawResults, rewResults);
        }

        // ── Boundary summary ──
        System.out.println("\n=== Boundary Query Score Summary ===");
        printBoundarySummary(allQueries, rawResults, rewResults);
    }

    private QResult evaluate(EvalQueries.EvalQuery eq, String rewritten,
                              List<EmbeddingMatch<TextSegment>> matches) {
        double mrr = 0;
        int firstHitPos = -1;
        boolean hit5 = false, hit20 = false;
        int gtIn5 = 0, gtIn20 = 0;
        double top1 = matches.isEmpty() ? 0 : matches.get(0).score();
        double cappedR5 = 0, cappedR20 = 0;

        String[] gt = eq.groundTruth();
        if (gt != null) {
            Set<String> uniqueTitlesIn5 = new HashSet<>();
            Set<String> uniqueTitlesIn20 = new HashSet<>();
            for (int i = 0; i < matches.size(); i++) {
                var meta = matches.get(i).embedded().metadata();
                String title = meta != null ? meta.getString("title") : null;
                if (title != null && matchesGt(title, gt)) {
                    if (firstHitPos < 0) firstHitPos = i + 1;
                    if (i < 5) { gtIn5++; uniqueTitlesIn5.add(title); }
                    gtIn20++; uniqueTitlesIn20.add(title);
                }
            }
            if (firstHitPos > 0) {
                mrr = 1.0 / firstHitPos;
                hit20 = true;
                hit5 = firstHitPos <= 5;
            }

            if (eq.multiTarget()) {
                int denom5 = Math.min(gt.length, 5);
                int denom20 = Math.min(gt.length, 20);
                cappedR5 = denom5 > 0 ? (double) uniqueTitlesIn5.size() / denom5 : 0;
                cappedR20 = denom20 > 0 ? (double) uniqueTitlesIn20.size() / denom20 : 0;
            } else {
                cappedR5 = gtIn5 > 0 ? 1.0 : 0.0;
                cappedR20 = gtIn20 > 0 ? 1.0 : 0.0;
            }
        }
        return new QResult(eq.query(), rewritten, hit5, hit20, mrr, gtIn5, gtIn20, top1,
                cappedR5, cappedR20);
    }

    private boolean matchesGt(String title, String[] gt) {
        String lower = title.toLowerCase();
        for (String g : gt) {
            if (lower.contains(g.toLowerCase())) return true;
        }
        return false;
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

    // ── Output ──

    private void printInScopeTable(List<EvalQueries.EvalQuery> all,
                                    List<QResult> raw, List<QResult> rew) {
        System.out.printf("%-4s %-3s %-30s %5s %6s %6s  %5s %6s %6s  %-30s%n",
                "ID", "TGT", "QUERY", "HIT5", "CR5", "MRR", "HIT5", "CR5", "MRR", "REWRITTEN");
        System.out.println("-".repeat(130));
        for (int i = 0; i < all.size(); i++) {
            var eq = all.get(i);
            if (eq.category() == EvalQueries.Category.BOUNDARY) continue;
            var r = raw.get(i);
            var w = rew.get(i);
            String tgtFlag = eq.multiTarget() ? " M " : " S ";
            System.out.printf("%-4s %-3s %-30s %5s %6s %6s  %5s %6s %6s  %-30s%n",
                    eq.id(), tgtFlag, trunc(eq.query(), 30),
                    r.hit5 ? "HIT" : "MIS", fmt(r.cappedR5), fmt(r.mrr),
                    w.hit5 ? "HIT" : "MIS", fmt(w.cappedR5), fmt(w.mrr),
                    trunc(w.rewritten, 30));
        }
    }

    private void printBoundaryTable(List<EvalQueries.EvalQuery> all,
                                     List<QResult> raw, List<QResult> rew) {
        System.out.printf("%-4s %-38s %8s  %8s  %s%n",
                "ID", "QUERY", "RAW_TOP1", "REW_TOP1", "NOTE");
        System.out.println("-".repeat(85));
        for (int i = 0; i < all.size(); i++) {
            var eq = all.get(i);
            if (eq.category() != EvalQueries.Category.BOUNDARY) continue;
            var r = raw.get(i);
            var w = rew.get(i);
            String note = r.top1Score > 0.82
                    ? "WARN: high score for boundary query"
                    : "OK: low score, healthy rejection";
            System.out.printf("%-4s %-38s %8.4f  %8.4f  %s%n",
                    eq.id(), trunc(eq.query(), 38),
                    r.top1Score, w.top1Score, note);
        }
    }

    private void printAggregate(List<EvalQueries.EvalQuery> all,
                                 List<QResult> raw, List<QResult> rew,
                                 boolean boundary) {
        double rawHit5 = 0, rawMrr = 0, rawPrec = 0, rawCapped5 = 0, rawCapped20 = 0;
        double rewHit5 = 0, rewMrr = 0, rewPrec = 0, rewCapped5 = 0, rewCapped20 = 0;
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            var eq = all.get(i);
            if ((eq.category() == EvalQueries.Category.BOUNDARY) == boundary) {
                n++;
                var r = raw.get(i);
                var w = rew.get(i);
                rawHit5 += r.hit5 ? 1 : 0;
                rawMrr += r.mrr;
                rawPrec += r.gtIn5 / 5.0;
                rawCapped5 += r.cappedR5;
                rawCapped20 += r.cappedR20;
                rewHit5 += w.hit5 ? 1 : 0;
                rewMrr += w.mrr;
                rewPrec += w.gtIn5 / 5.0;
                rewCapped5 += w.cappedR5;
                rewCapped20 += w.cappedR20;
            }
        }
        if (n == 0) return;
        rawHit5 /= n; rawMrr /= n; rawPrec /= n; rawCapped5 /= n; rawCapped20 /= n;
        rewHit5 /= n; rewMrr /= n; rewPrec /= n; rewCapped5 /= n; rewCapped20 /= n;

        System.out.printf("%-22s  %8s  %8s  %8s%n", "Metric", "RAW", "REWRITTEN", "DELTA");
        System.out.println("-".repeat(52));
        printRow("Hit@5", rawHit5, rewHit5);
        printRow("CappedR@5", rawCapped5, rewCapped5);
        printRow("CappedR@20", rawCapped20, rewCapped20);
        printRow("MRR", rawMrr, rewMrr);
        printRow("Precision@5", rawPrec, rewPrec);

        // Count improved/degraded (on CappedR@5)
        int improved = 0, degraded = 0;
        for (int i = 0; i < all.size(); i++) {
            var eq = all.get(i);
            if ((eq.category() == EvalQueries.Category.BOUNDARY) == boundary) {
                if (rew.get(i).cappedR5 > raw.get(i).cappedR5 + 0.001) improved++;
                else if (rew.get(i).cappedR5 < raw.get(i).cappedR5 - 0.001) degraded++;
            }
        }
        System.out.printf("Queries improved (CappedR@5): %d  |  degraded: %d%n", improved, degraded);
    }

    private void printCategoryAggregate(EvalQueries.Category cat,
                                         List<EvalQueries.EvalQuery> all,
                                         List<QResult> raw, List<QResult> rew) {
        double rawHit5 = 0, rawCapped5 = 0, rawMrr = 0;
        double rewHit5 = 0, rewCapped5 = 0, rewMrr = 0;
        int n = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).category() == cat) {
                n++;
                rawHit5 += raw.get(i).hit5 ? 1 : 0;
                rawCapped5 += raw.get(i).cappedR5;
                rawMrr += raw.get(i).mrr;
                rewHit5 += rew.get(i).hit5 ? 1 : 0;
                rewCapped5 += rew.get(i).cappedR5;
                rewMrr += rew.get(i).mrr;
            }
        }
        if (n == 0) return;
        rawHit5 /= n; rawCapped5 /= n; rawMrr /= n;
        rewHit5 /= n; rewCapped5 /= n; rewMrr /= n;

        System.out.printf("%-22s n=%-2d  Hit@5: %5s → %5s  CR5: %5s → %5s  MRR: %5s → %5s%n",
                "[" + cat + "]", n,
                pct(rawHit5), pct(rewHit5),
                fmt(rawCapped5), fmt(rewCapped5),
                fmt(rawMrr), fmt(rewMrr));
    }

    private void printBoundarySummary(List<EvalQueries.EvalQuery> all,
                                       List<QResult> raw, List<QResult> rew) {
        double rawMin = 1, rawMax = 0, rewMin = 1, rewMax = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).category() != EvalQueries.Category.BOUNDARY) continue;
            rawMin = Math.min(rawMin, raw.get(i).top1Score);
            rawMax = Math.max(rawMax, raw.get(i).top1Score);
            rewMin = Math.min(rewMin, rew.get(i).top1Score);
            rewMax = Math.max(rewMax, rew.get(i).top1Score);
        }
        System.out.printf("RAW  top-1 score range: %.4f ~ %.4f  (lower = better boundary detection)%n",
                rawMin, rawMax);
        System.out.printf("REW  top-1 score range: %.4f ~ %.4f%n", rewMin, rewMax);
        System.out.println("Note: boundary queries are Layer 0 (routing) concern. "
                + "Low scores here indicate healthy inter-query differentiation.");
    }

    // ── Helpers ──

    private void printRow(String name, double raw, double rew) {
        double d = rew - raw;
        String dir = d >= 0.001 ? "+" : d <= -0.001 ? "" : " ";
        System.out.printf("%-20s  %7.3f  %7.3f  %s%.3f%n", name, raw, rew, dir, d);
    }

    private static String pct(double v) { return String.format("%.0f%%", v * 100); }
    private static String fmt(double v) { return String.format("%.3f", v); }
    private static String trunc(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }
    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
