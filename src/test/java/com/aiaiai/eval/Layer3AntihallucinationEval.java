package com.aiaiai.eval;

import com.aiaiai.reranker.DashScopeReranker;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeServerlessIndexConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 3: Generation Anti-Hallucination Evaluation.
 * 6 metrics across 35 queries (27 in-scope + 8 boundary).
 *
 * Delegates: metrics → {@link Layer3Metrics}, synonyms → {@link SynonymExpander},
 *            output → {@link Layer3Output}.
 */
public class Layer3AntihallucinationEval {

    record EvalContext(String answer, List<String> chunks, List<Embedding> chunkEmbs,
                       boolean searchCalled, Set<String> calledTools,
                       String formattedResult) {}

    public record Layer3Result(String id, String query, String categoryLabel,
                        double faithfulnessLower, double hep,
                        double citationRecall, double citationPrecision,
                        boolean correctUncertain, boolean correctBoundary,
                        double groundedness, int numClaims, int numEntities,
                        int numCitations, String warnings) {
        public double citationF1() {
            double denom = citationRecall + citationPrecision;
            return denom > 0 ? 2 * citationRecall * citationPrecision / denom : 0;
        }
    }

    private interface Layer3Assistant {
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    private static class PreloadedSearchTool {
        private final String preloadedResults;
        private final Set<String> calledTools;

        PreloadedSearchTool(String preloadedResults, Set<String> calledTools) {
            this.preloadedResults = preloadedResults;
            this.calledTools = calledTools;
        }

        @Tool("检索三维重建/点云补全知识库。当用户询问论文方法、网络结构、损失函数、训练策略、"
                + "数据集、指标、实验结论或方法对比时调用。不可用于闲聊或偏好记忆写入。")
        public String searchKnowledge(
                @P(value = "检索查询词", required = true) String query) {
            calledTools.add("searchKnowledge");
            return preloadedResults;
        }
    }

    private final List<Layer3Result> results = new ArrayList<>();
    private final List<String> divergenceWarnings = new ArrayList<>();
    private int totalClaimsJudged = 0;
    private int judgeCalls = 0;

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

        String rerankKey = env("RERANKER_API_KEY",
                env("EMBEDDING_V2_API_KEY", env("EMBEDDING_API_KEY", "")));
        String rerankBase = env("RERANKER_BASE_URL", "https://dashscope.aliyuncs.com");
        String rerankModel = env("RERANKER_MODEL", "gte-rerank");

        boolean llmJudge = "true".equalsIgnoreCase(System.getenv("LAYER3_LLM_JUDGE"));

        if (embKey.isBlank() || llmKey.isBlank() || pineKey.isBlank()) {
            System.out.println("Missing API keys.");
            return;
        }

        EmbeddingModel embModelRaw = OpenAiEmbeddingModel.builder()
                .apiKey(embKey).baseUrl(embUrl).modelName(embModel)
                .dimensions(dims).maxSegmentsPerBatch(10)
                .timeout(Duration.ofSeconds(60)).build();
        Layer3Metrics.EmbeddingModelAdapter embAdapter = text -> embModelRaw.embed(text).content();

        PineconeEmbeddingStore store = PineconeEmbeddingStore.builder()
                .apiKey(pineKey).index(pineIndex).nameSpace("knowledge_v2")
                .createIndex(PineconeServerlessIndexConfig.builder()
                        .cloud("AWS").region("us-east-1").dimension(dims).build())
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(llmKey).baseUrl(llmUrl).modelName(llmModel)
                .temperature(0.0).maxTokens(512)
                .timeout(Duration.ofSeconds(60)).build();

        ScoringModel reranker = rerankKey.isBlank() ? null
                : new DashScopeReranker(rerankBase, rerankKey, rerankModel);

        ChatModel judgeModel = llmJudge ? OpenAiChatModel.builder()
                .apiKey(llmKey).baseUrl(llmUrl).modelName(llmModel)
                .temperature(0.0).maxTokens(10)
                .timeout(Duration.ofSeconds(30)).build() : null;

        SynonymExpander synonymExpander = new SynonymExpander();

        List<Layer3EvalQueries.Layer3Query> l3Queries = Layer3EvalQueries.all();
        List<EvalQueries.EvalQuery> boundaryQueries = EvalQueries.all().stream()
                .filter(eq -> eq.category() == EvalQueries.Category.BOUNDARY).toList();

        System.out.println("=== Layer 3: Generation Anti-Hallucination Evaluation ===\n");
        System.out.printf("Embedding: %s | LLM: %s | Reranker: %s | LLM-Judge: %s%n",
                embModel, llmModel, rerankModel, llmJudge ? "ON" : "OFF");
        System.out.printf("Queries: %d in-scope + %d boundary = %d total%n%n",
                l3Queries.size(), boundaryQueries.size(), l3Queries.size() + boundaryQueries.size());

        for (var l3q : l3Queries) {
            processInScope(l3q, l3q.category(), embModelRaw, store, reranker, chatModel,
                    embAdapter, synonymExpander, llmJudge, judgeModel);
        }

        int boundaryCorrect = 0;
        for (var bq : boundaryQueries) {
            if (processBoundary(bq, embModelRaw, store, reranker, chatModel)) boundaryCorrect++;
        }

        Layer3Output.printDetailTable(results);
        Layer3Output.printCategoryBreakdown(results);
        Layer3Output.printAggregate(results, boundaryQueries.size(), boundaryCorrect);
        if (!divergenceWarnings.isEmpty()) {
            System.out.println("\n=== Groundedness-HEP Divergence Warnings ===");
            divergenceWarnings.forEach(System.out::println);
        }
        Layer3Output.printCost(results.size(), judgeCalls, totalClaimsJudged);
        System.out.println("\nDone.");
    }

    private void processInScope(Layer3EvalQueries.Layer3Query l3q,
                                 Layer3EvalQueries.Layer3Category category,
                                 EmbeddingModel embModel,
                                 PineconeEmbeddingStore store, ScoringModel reranker,
                                 ChatModel chatModel, Layer3Metrics.EmbeddingModelAdapter embAdapter,
                                 SynonymExpander expander, boolean llmJudge, ChatModel judgeModel) {
        EvalContext ctx;
        try {
            ctx = retrieveAndGenerate(l3q.query(), category, embModel, store, reranker, chatModel);
        } catch (Exception e) {
            System.out.printf("[%s] ERROR: %s%n", l3q.id(), e.getMessage());
            results.add(new Layer3Result(l3q.id(), l3q.query(), l3q.category().name(),
                    0, 0, 0, 0, false, false, 0, 0, 0, 0, "GENERATION_FAILED"));
            return;
        }

        if (!ctx.searchCalled) {
            results.add(new Layer3Result(l3q.id(), l3q.query(), l3q.category().name(),
                    0, 0, 0, 0, l3q.expectUncertain(), false, 0, 0, 0, 0,
                    "searchKnowledge NOT called"));
            return;
        }

        String answer = ctx.answer;

        // Post-generation citation enforcement (like NeMo output rail)
        // Skip if answer already says "I don't know" — uncertainty answers need no citations
        List<String> citations = Layer3Metrics.extractCitationMarkers(answer);
        if (citations.isEmpty()
                && !Layer3Metrics.hasUncertaintyMarker(answer)
                && ctx.chunks != null && !ctx.chunks.isEmpty()) {
            String followUp = "请为以上回答中的每个关键事实添加引用来源。格式：\"> 来源：[引用来源 #N]\"，"
                    + "其中 N 是你看到的检索结果编号。不得在末尾集中标注，每个事实必须单独标注。";

            Set<String> calledTools2 = ConcurrentHashMap.newKeySet();
            PreloadedSearchTool tool2 = new PreloadedSearchTool(ctx.formattedResult, calledTools2);
            Layer3Assistant assistant2 = AiServices.builder(Layer3Assistant.class)
                    .chatModel(chatModel)
                    .chatMemoryProvider(mid -> MessageWindowChatMemory.builder()
                            .id(mid).maxMessages(20).build())
                    .tools(tool2)
                    .systemMessageProvider(sid -> Layer3Output.SYSTEM_PROMPT)
                    .build();

            try {
                String sid = "eval-citefix-" + UUID.randomUUID().toString().substring(0, 8);
                answer = assistant2.chat(sid,
                        "之前的回答：\n" + answer + "\n\n" + followUp);
                System.out.printf("  [%s] citation re-prompt triggered%n", l3q.id());
            } catch (Exception e) {
                System.out.printf("  [%s] citation re-prompt failed: %s%n", l3q.id(), e.getMessage());
            }
        }

        Set<String> entities = Layer3Metrics.extractHardEntities(answer);
        int verified = Layer3Metrics.countVerifiedEntities(entities, ctx.chunks, expander);
        double hep = entities.isEmpty() ? 1.0 : (double) verified / entities.size();

        citations = Layer3Metrics.extractCitationMarkers(answer);
        int factualClaims = Layer3Metrics.countFactualClaims(answer);
        double cr = factualClaims > 0 ? (double) citations.size() / factualClaims : 0;
        int correctCits = Layer3Metrics.countCorrectCitations(
                answer, citations, ctx.chunks, expander);
        double cp = citations.isEmpty() ? 1.0 : (double) correctCits / citations.size();

        boolean uncCorrect = !l3q.expectUncertain()
                || Layer3Metrics.hasUncertaintyMarker(answer);

        List<String> sentences = Layer3Metrics.splitSentences(answer);
        double groundedness = Layer3Metrics.computeGroundedness(sentences, ctx.chunkEmbs, embAdapter);

        List<String> claims = Layer3Metrics.extractAtomicClaims(answer);
        int supportedDeterm = Layer3Metrics.countDeterministicSupported(
                claims, ctx.chunks, expander);
        double faithLower = claims.isEmpty() ? 1.0 : (double) supportedDeterm / claims.size();

        String warnings = "";
        if (groundedness > 0.80 && hep < 0.90) {
            warnings = "高锚定(" + Layer3Output.fmt(groundedness) + ")+低实体验证("
                    + Layer3Output.fmt(hep) + ") — 疑似流畅但编造";
            divergenceWarnings.add("⚠ " + l3q.id() + ": " + warnings);
        }

        results.add(new Layer3Result(l3q.id(), l3q.query(), l3q.category().name(),
                faithLower, hep, cr, cp, uncCorrect, false,
                groundedness, claims.size(), entities.size(), citations.size(), warnings));

        if (llmJudge && judgeModel != null) {
            refineFaithfulness(l3q.id(), claims, ctx.chunks, judgeModel);
        }
    }

    private boolean processBoundary(EvalQueries.EvalQuery bq, EmbeddingModel embModel,
                                     PineconeEmbeddingStore store, ScoringModel reranker,
                                     ChatModel chatModel) {
        String emptyResult = "[检索结果] 未在知识库中找到相关内容。\n\n"
                + "[系统指令] 检索无结果。请按照领域边界规则回答，不得编造。";
        Set<String> calledTools = ConcurrentHashMap.newKeySet();
        PreloadedSearchTool tool = new PreloadedSearchTool(emptyResult, calledTools);
        Layer3Assistant assistant = AiServices.builder(Layer3Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(mid -> MessageWindowChatMemory.builder()
                        .id(mid).maxMessages(10).build())
                .tools(tool)
                .systemMessageProvider(sid -> Layer3Output.SYSTEM_PROMPT)
                .build();

        String answer;
        try {
            answer = assistant.chat("eval-bnd-" + UUID.randomUUID().toString().substring(0, 8),
                    bq.query());
        } catch (Exception e) {
            System.out.printf("[%s] ERROR: %s%n", bq.id(), e.getMessage());
            return false;
        }
        boolean correct = !calledTools.contains("searchKnowledge")
                || Layer3Metrics.hasBoundaryMarker(answer);
        results.add(new Layer3Result(bq.id(), bq.query(), "BOUNDARY",
                0, 0, 0, 0, false, correct,
                0, 0, 0, 0, correct ? "gatekeeping OK" : "MISSING_GATEKEEPING"));
        return correct;
    }

    // ═══════════════════════════════════════════════════════════════

    private EvalContext retrieveAndGenerate(String query, Layer3EvalQueries.Layer3Category category,
                                             EmbeddingModel embModel,
                                             PineconeEmbeddingStore store, ScoringModel reranker,
                                             ChatModel chatModel) {
        int maxResults = switch (category) {
            case CITATION_CHECK, COMPARISON -> 30;
            default -> 20;
        };

        Embedding qEmb = embModel.embed(query).content();
        var sr = store.search(EmbeddingSearchRequest.builder()
                .queryEmbedding(qEmb).maxResults(maxResults).minScore(0.0).build());
        List<EmbeddingMatch<TextSegment>> matches = sr.matches();

        List<String> chunks = new ArrayList<>();
        List<Embedding> chunkEmbs = new ArrayList<>();
        String formatted;

        if (matches.isEmpty()) {
            formatted = "[检索结果] 未在知识库中找到相关内容。\n\n"
                    + "[系统指令] 检索无结果。你必须按照\"三、不确定规则\"回答，"
                    + "使用模板：\"根据目前知识库中的资料，暂时无法回答这个问题。\""
                    + "严禁基于训练数据编造任何事实性陈述。";
        } else if (reranker != null) {
            List<TextSegment> segments = new ArrayList<>();
            for (var m : matches) segments.add(m.embedded());
            Response<List<Double>> scores = reranker.scoreAll(segments, query);
            List<Integer> indices = new ArrayList<>();
            for (int i = 0; i < scores.content().size(); i++) indices.add(i);
            indices.sort((a, b) -> Double.compare(scores.content().get(b), scores.content().get(a)));

            int limit = switch (category) {
                case CITATION_CHECK, COMPARISON -> Math.min(10, indices.size());
                default -> Math.min(5, indices.size());
            };
            int highCount = 0;
            StringBuilder sb = new StringBuilder("[知识库检索结果（已精排）]\n");
            for (int i = 0; i < limit; i++) {
                int idx = indices.get(i);
                var m = matches.get(idx);
                double s = scores.content().get(idx);
                String conf = Layer3Metrics.normalizeRerankConf(s);
                if ("HIGH".equals(conf)) highCount++;
                String section = Layer3Metrics.labelOf(m);
                sb.append(String.format("--- [引用来源 #%d] (相关度: %.2f, 置信度: %s, 章节: %s) ---\n",
                        i + 1, s, conf, section.isEmpty() ? "—" : section));
                sb.append(m.embedded().text()).append("\n");
                chunks.add(m.embedded().text());
                chunkEmbs.add(m.embedding());
            }
            formatted = sb.toString();
            if (highCount == 0 && !chunks.isEmpty()) {
                formatted = "[系统指令] 以下检索结果均为中低置信度，仅供背景参考，"
                        + "不可作为核心论据。若不足以回答问题，必须使用不确定模板。\n\n"
                        + formatted;
            }
        } else {
            int total = switch (category) {
                case CITATION_CHECK, COMPARISON -> Math.min(10, matches.size());
                default -> Math.min(5, matches.size());
            };
            int highCount = 0;
            StringBuilder sb = new StringBuilder("[知识库检索结果]\n");
            for (int i = 0; i < total; i++) {
                var m = matches.get(i);
                String conf = Layer3Metrics.normalizeCosConf(m.score());
                if ("HIGH".equals(conf)) highCount++;
                String section = Layer3Metrics.labelOf(m);
                sb.append(String.format("--- [引用来源 #%d] (相关度: %.2f, 置信度: %s, 章节: %s) ---\n",
                        i + 1, m.score(), conf, section.isEmpty() ? "—" : section));
                sb.append(m.embedded().text()).append("\n");
                chunks.add(m.embedded().text());
                chunkEmbs.add(m.embedding());
            }
            formatted = sb.toString();
            if (highCount == 0 && !chunks.isEmpty()) {
                formatted = "[系统指令] 以下检索结果均为中低置信度，仅供背景参考，"
                        + "不可作为核心论据。若不足以回答问题，必须使用不确定模板。\n\n"
                        + formatted;
            }
        }

        // Pre-generation entity coverage check (migrated from HEP logic, like Dokis)
        if (!chunks.isEmpty()) {
            boolean queryAsksForNum = query.matches(".*(是多少|几个|多少个|什么数值|"
                    + "什么值|多少|F-score|维度|大小|数量|具体数字).*");
            if (queryAsksForNum) {
                Set<String> chunkEntities = new LinkedHashSet<>();
                for (String chunk : chunks) {
                    chunkEntities.addAll(Layer3Metrics.extractHardEntities(chunk));
                }
                boolean hasNumeric = chunkEntities.stream()
                        .anyMatch(e -> java.util.regex.Pattern.compile("\\d").matcher(e).find());
                if (!hasNumeric) {
                    formatted = "[系统指令] 检索结果提到了相关方法/模块，"
                            + "但不包含任何具体数值。你必须使用不确定模板，"
                            + "不得基于训练数据编造数值。\n\n" + formatted;
                }
            }
        }

        Set<String> calledTools = ConcurrentHashMap.newKeySet();
        PreloadedSearchTool tool = new PreloadedSearchTool(formatted, calledTools);
        Layer3Assistant assistant = AiServices.builder(Layer3Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(mid -> MessageWindowChatMemory.builder()
                        .id(mid).maxMessages(10).build())
                .tools(tool)
                .systemMessageProvider(sid -> Layer3Output.SYSTEM_PROMPT)
                .build();

        String answer = assistant.chat("eval-" + UUID.randomUUID().toString().substring(0, 8), query);
        return new EvalContext(answer != null ? answer : "", chunks, chunkEmbs,
                calledTools.contains("searchKnowledge"), calledTools, formatted);
    }

    private void refineFaithfulness(String queryId, List<String> claims, List<String> chunks,
                                     ChatModel judgeModel) {
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            if (!r.id().equals(queryId)) continue;
            int supported = 0;
            String topChunks = String.join("\n\n",
                    chunks.subList(0, Math.min(3, chunks.size())));
            for (String claim : claims) {
                judgeCalls++;
                try {
                    String judgeInput = "Claim: " + claim + "\n\nEvidence:\n" + topChunks;
                    String resp = judgeModel.chat(SystemMessage.from(Layer3Output.JUDGE_PROMPT),
                            dev.langchain4j.data.message.UserMessage.from(judgeInput))
                            .aiMessage().text();
                    if (resp != null && resp.strip().toUpperCase().startsWith("YES")) {
                        supported++;
                        totalClaimsJudged++;
                    }
                } catch (Exception e) { /* skip */ }
            }
            double newFaith = claims.isEmpty() ? 1.0 : (double) supported / claims.size();
            results.set(i, new Layer3Result(r.id(), r.query(), r.categoryLabel(),
                    newFaith, r.hep(), r.citationRecall(), r.citationPrecision(),
                    r.correctUncertain(), r.correctBoundary(), r.groundedness(),
                    r.numClaims(), r.numEntities(), r.numCitations(), r.warnings()));
            break;
        }
    }

    // ── helpers ──

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
