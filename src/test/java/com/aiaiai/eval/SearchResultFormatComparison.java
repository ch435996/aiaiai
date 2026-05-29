package com.aiaiai.eval;

import com.aiaiai.retrieval.RetrievalServiceImpl;
import com.aiaiai.retrieval.SearchResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import dev.langchain4j.store.embedding.pinecone.PineconeServerlessIndexConfig;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compare old text format vs new JSON format for searchKnowledge results.
 * Prints both intermediate tool results and the LLM's final answer side by side.
 */
public class SearchResultFormatComparison {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final String EVAL_PROMPT = """
            你是三维重建/点云补全领域的智能科研助手。

            回答规则：
            - 只能使用下方检索结果中提供的证据来回答
            - 每个事实性陈述后标注引用来源，格式：> 来源：[引用来源 #N]
            - 如果检索结果不足以完整回答，使用模板："根据目前知识库中的资料，暂时无法回答这个问题。"
            """;

    record ComparisonRow(String query, String textResult, String jsonResult,
                         String textAnswer, String jsonAnswer) {}

    @Test
    void compare() {
        String embKey = env("EMBEDDING_V2_API_KEY", env("EMBEDDING_API_KEY", ""));
        String embUrl = env("EMBEDDING_V2_BASE_URL",
                env("EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        String embModel = env("EMBEDDING_V2_MODEL", "text-embedding-v4");
        int dims = Integer.parseInt(env("EMBEDDING_V2_DIMENSIONS", "1024"));

        String pineKey = env("PINECONE_API_KEY", "");
        String pineIndex = env("PINECONE_INDEX", "aiaiai-knowledge");

        String llmKey = env("DEEPSEEK_API_KEY", "");
        String llmUrl = env("DEEPSEEK_BASE_URL", "https://api.deepseek.com");
        String llmModel = env("DEEPSEEK_MODEL", "deepseek-chat");

        String embVersion = env("EMBEDDING_VERSION", "v2");

        if (embKey.isBlank() || pineKey.isBlank() || llmKey.isBlank()) {
            System.out.println("Missing API keys — set EMBEDDING_V2_API_KEY, PINECONE_API_KEY, DEEPSEEK_API_KEY");
            return;
        }

        // ── Wire up ──
        EmbeddingModel embV3 = OpenAiEmbeddingModel.builder()
                .apiKey(embKey).baseUrl(embUrl).modelName("text-embedding-v3")
                .dimensions(dims).maxSegmentsPerBatch(10)
                .timeout(Duration.ofSeconds(60)).build();
        EmbeddingModel embV4 = OpenAiEmbeddingModel.builder()
                .apiKey(embKey).baseUrl(embUrl).modelName("text-embedding-v4")
                .dimensions(dims).maxSegmentsPerBatch(10)
                .timeout(Duration.ofSeconds(60)).build();
        EmbeddingStore<?> store = PineconeEmbeddingStore.builder()
                .apiKey(pineKey).index(pineIndex).nameSpace("knowledge_v2")
                .createIndex(PineconeServerlessIndexConfig.builder()
                        .cloud("AWS").region("us-east-1").dimension(dims).build())
                .build();

        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(llmKey).baseUrl(llmUrl).modelName(llmModel)
                .temperature(0.0).timeout(Duration.ofSeconds(120)).build();

        @SuppressWarnings("unchecked")
        var retrievalService = new RetrievalServiceImpl(
                embV3, embV4,
                (dev.langchain4j.store.embedding.EmbeddingStore) store,
                (dev.langchain4j.store.embedding.EmbeddingStore) store,
                null,  // no reranker
                20, 0.0, 0.65, 0.85, 0.65, 0.85,
                embVersion, 5, 0.70, 0.30);

        // ── Test queries ──
        String[] queries = {
                "SnowflakeNet 的编码器结构是什么？",
                "PCN 和 FoldingNet 在补全任务上的主要差异？",
                "点云补全常用的损失函数有哪些？"
        };

        List<ComparisonRow> rows = new ArrayList<>();

        for (String q : queries) {
            System.out.println("\n══════════════════════════════════════════════");
            System.out.println("QUERY: " + q);
            System.out.println("══════════════════════════════════════════════");

            // ── Old text format ──
            String textResult = retrievalService.search(q, 5);
            System.out.println("\n─── OLD TEXT FORMAT ───");
            System.out.println(truncate(textResult, 800));

            // ── New JSON format ──
            SearchResult sr = retrievalService.searchStructured(q, 5);
            String jsonResult = toSearchResultJson(sr);
            System.out.println("\n─── NEW JSON FORMAT ───");
            System.out.println(truncate(jsonResult, 800));

            // ── Token estimate ──
            System.out.printf("%n  text ~%d tokens | json ~%d tokens%n",
                    estimateTokens(textResult), estimateTokens(jsonResult));

            // ── LLM answer with text format ──
            String textAnswer = askLlm(chatModel, textResult, q);
            System.out.println("\n─── LLM ANSWER (text format) ───");
            System.out.println(truncate(textAnswer, 600));

            // ── LLM answer with JSON format ──
            String jsonAnswer = askLlm(chatModel, jsonResult, q);
            System.out.println("\n─── LLM ANSWER (JSON format) ───");
            System.out.println(truncate(jsonAnswer, 600));

            rows.add(new ComparisonRow(q, textResult, jsonResult, textAnswer, jsonAnswer));
        }

        // ── Summary ──
        System.out.println("\n\n══════════════════════════════════════════════");
        System.out.println("SUMMARY: text vs JSON for " + rows.size() + " queries");
        System.out.println("══════════════════════════════════════════════");
        for (var row : rows) {
            System.out.printf("Query: %s%n", truncate(row.query, 80));
            System.out.printf("  text tokens ~%d | json tokens ~%d%n",
                    estimateTokens(row.textResult), estimateTokens(row.jsonResult));
            System.out.printf("  text answer: %d chars | json answer: %d chars%n",
                    row.textAnswer.length(), row.jsonAnswer.length());
        }
    }

    // ── JSON serialization (mirrors AiaiaiTools.toSearchResultJson) ──

    private static String toSearchResultJson(SearchResult sr) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("count", sr.count());
        map.put("maxConfidence", sr.maxConfidence());
        map.put("snippets", sr.snippets());
        try {
            String json = objectMapper.writeValueAsString(map);
            if (sr.count() == 0) {
                return json + "\n\n[系统指令] 检索无结果，必须使用不确定模板回答，禁止编造。";
            }
            return json;
        } catch (Exception e) {
            return "{\"error\": \"serialization failed\"}";
        }
    }

    // ── LLM call ──

    private String askLlm(ChatModel model, String searchResult, String query) {
        try {
            var resp = model.chat(
                    SystemMessage.from(EVAL_PROMPT),
                    UserMessage.from(query + "\n\n[检索结果]\n" + searchResult));
            return resp.aiMessage().text();
        } catch (Exception e) {
            return "LLM call failed: " + e.getMessage();
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "null";
        return s.length() > maxLen ? s.substring(0, maxLen) + "…" : s;
    }

    private static int estimateTokens(String s) {
        if (s == null) return 0;
        return (int) (s.length() * 0.4);
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return v != null ? v : fallback;
    }
}
