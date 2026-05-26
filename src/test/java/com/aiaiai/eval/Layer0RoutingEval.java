package com.aiaiai.eval;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Layer 0: Intent Routing Accuracy — Tool selection correctness.
 *
 * Core question: does the Agent pick the right tool(s) for each query?
 * This is orthogonal to retrieval quality — a perfect RAG pipeline is
 * useless if the LLM never decides to call searchKnowledge.
 *
 * Method: AiServices with no-op recording tools, one query per fresh session.
 * Each tool call is recorded, then compared against expected/forbidden sets.
 *
 * Metrics:
 *   Routing Accuracy = % of queries passing all checks
 *   Tool-level Precision / Recall
 *   Per-category breakdown
 */
public class Layer0RoutingEval {

    private static final String SYSTEM_PROMPT = """
            你是三维重建/点云补全领域的智能科研助手。

            工具使用指南：
            - searchKnowledge：仅在用户询问三维重建/点云补全领域的论文事实、方法细节、实验结果、数据集
              或指标时调用。用户问题明显超出此范围（如编程、自动驾驶、医疗影像、通用工具推荐等）时，
              严禁调用 searchKnowledge，直接简要回答并提示领域边界。
            - saveMemory：仅在用户明确要求"记住"或透露课题组长期稳定偏好时调用
            - recallMemory：在用户问题涉及课题组偏好、历史规范时调用，检查是否有相关长期记忆
            - 闲聊、寒暄或你能自信回答的问题，直接回复即可

            回答原则：
            - 优先使用知识库证据，证据不足时明确说明"不确定"
            - 涉及论文细节和实验配置时，先检索再回答
            - 超出三维重建/点云补全领域的问题可简要回答，但需提示领域边界
            """;

    // Per-query recording
    private static final Set<String> calledTools = ConcurrentHashMap.newKeySet();

    private interface Layer0Assistant {
        String chat(@MemoryId String sessionId, @UserMessage String message);
    }

    public static class RecordingTools {

        @Tool("检索三维重建/点云补全知识库。当用户询问论文方法、网络结构、损失函数、训练策略、"
                + "数据集、指标、实验结论或方法对比时调用。不可用于闲聊或偏好记忆写入。")
        public String searchKnowledge(
                @P(value = "检索查询词", required = true) String query) {
            calledTools.add("searchKnowledge");
            return "已检索知识库，未找到精确匹配的条目。建议基于通用背景知识回答，并提示用户这可能超出知识库范围。";
        }

        @Tool("将信息保存到长期记忆。仅在用户明确要求记住某事、透露课题组稳定偏好"
                + "或长期有效的研究约束时调用。不可保存临时情绪或一次性指令。")
        public String saveMemory(
                @P(value = "需要保存的记忆内容", required = true) String content) {
            calledTools.add("saveMemory");
            return "已成功保存到长期记忆，后续对话中可召回。";
        }

        @Tool("从长期记忆中召回相关信息。当用户询问的内容可能涉及之前存储的偏好、"
                + "课题组规范、研究方向等记忆时调用。")
        public String recallMemory(
                @P(value = "用于搜索长期记忆的查询词", required = true) String query) {
            calledTools.add("recallMemory");
            return "未在长期记忆中找到与此查询相关的内容。";
        }
    }

    @Test
    void evaluate() {
        String key = env("DEEPSEEK_API_KEY", "");
        if (key.isBlank()) {
            System.out.println("DEEPSEEK_API_KEY not set.");
            return;
        }

        ChatModel chatModel = OpenAiChatModel.builder()
                .apiKey(key)
                .baseUrl(env("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
                .modelName(env("DEEPSEEK_MODEL", "deepseek-chat"))
                .temperature(0.0)
                .maxTokens(512)
                .timeout(Duration.ofSeconds(60))
                .build();

        RecordingTools tools = new RecordingTools();

        Layer0Assistant assistant = AiServices.builder(Layer0Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .build())
                .tools(tools)
                .maxSequentialToolsInvocations(10)
                .systemMessageProvider(sid -> SYSTEM_PROMPT)
                .build();

        List<EvalLayer0Queries.Layer0Query> allQueries = EvalLayer0Queries.all();

        System.out.println("=== Layer 0: Intent Routing Evaluation ===\n");

        // Per-query results
        int passed = 0, failed = 0;
        List<String> failures = new ArrayList<>();

        // Per-category stats
        Map<EvalLayer0Queries.Layer0Category, int[]> catStats = new LinkedHashMap<>();
        for (var cat : EvalLayer0Queries.Layer0Category.values()) {
            catStats.put(cat, new int[]{0, 0}); // [passed, total]
        }

        // Tool-level stats
        Map<String, int[]> toolStats = new LinkedHashMap<>();
        toolStats.put("searchKnowledge", new int[]{0, 0, 0, 0}); // TP, FP, TN, FN
        toolStats.put("saveMemory", new int[]{0, 0, 0, 0});
        toolStats.put("recallMemory", new int[]{0, 0, 0, 0});

        System.out.printf("%-5s %-50s %-16s %s%n", "ID", "QUERY", "EXPECTED", "ACTUAL");
        System.out.println("-".repeat(100));

        int idx = 0;
        for (var eq : allQueries) {
            idx++;
            String sessionId = "eval-L0-" + idx;
            calledTools.clear();

            try {
                assistant.chat(sessionId, eq.query());
            } catch (Exception e) {
                // Tool execution failure in eval mock won't happen,
                // but LLM API errors might
                System.out.printf("%-5s ERROR: %s%n", eq.id(),
                        e.getMessage().substring(0, Math.min(e.getMessage().length(), 60)));
                failed++;
                var st = catStats.get(eq.category());
                if (st != null) st[1]++;
                continue;
            }

            Set<String> actual = new HashSet<>(calledTools);
            Set<String> expected = eq.expectedTools();
            Set<String> forbidden = eq.forbiddenTools();

            boolean pass = true;
            List<String> reasons = new ArrayList<>();

            // Check: all expected tools were called
            for (String exp : expected) {
                if (!actual.contains(exp)) {
                    pass = false;
                    reasons.add("缺 " + exp);
                }
            }

            // Check: no forbidden tools were called
            for (String forb : forbidden) {
                if (actual.contains(forb)) {
                    pass = false;
                    reasons.add("禁调 " + forb);
                }
            }

            // Update per-tool stats
            updateToolStats(toolStats, "searchKnowledge", actual, expected, forbidden);
            updateToolStats(toolStats, "saveMemory", actual, expected, forbidden);
            updateToolStats(toolStats, "recallMemory", actual, expected, forbidden);

            // Update category stats
            var st = catStats.get(eq.category());
            if (st != null) {
                st[1]++;
                if (pass) st[0]++;
            }

            String expectedStr = expected.isEmpty() ? "无" : String.join("+", expected);
            String actualStr = actual.isEmpty() ? "无" : String.join("+", actual);
            String flag = pass ? "OK" : "FAIL: " + String.join(", ", reasons);

            if (pass) {
                passed++;
            } else {
                failed++;
                failures.add(String.format("%s | %s | 期望:%s 实际:%s | %s",
                        eq.id(), trunc(eq.query(), 30), expectedStr, actualStr, flag));
            }

            System.out.printf("%-5s %-50s %-16s %s%n",
                    eq.id(), trunc(eq.query(), 50),
                    expectedStr, actualStr + (pass ? "" : "  ← " + flag));
        }

        // ── Category breakdown ──
        System.out.println("\n=== Category Breakdown ===");
        System.out.printf("%-18s %6s %6s %8s%n", "CATEGORY", "PASSED", "TOTAL", "ACC%");
        System.out.println("-".repeat(42));
        for (var cat : EvalLayer0Queries.Layer0Category.values()) {
            int[] st = catStats.get(cat);
            double acc = st[1] > 0 ? (double) st[0] / st[1] * 100 : 0;
            System.out.printf("%-18s %6d %6d %7.1f%%%n",
                    "[" + cat + "]", st[0], st[1], acc);
        }

        // ── Overall ──
        int total = passed + failed;
        double accuracy = total > 0 ? (double) passed / total * 100 : 0;
        System.out.printf("%-18s %6d %6d %7.1f%%%n", "[OVERALL]", passed, total, accuracy);

        // ── Tool-level metrics ──
        System.out.println("\n=== Tool-Level Precision / Recall ===");
        System.out.printf("%-18s %10s %10s %10s %10s%n",
                "TOOL", "PRECISION", "RECALL", "F1", "FPR");
        System.out.println("-".repeat(58));
        for (var entry : toolStats.entrySet()) {
            int[] s = entry.getValue();
            int tp = s[0], fp = s[1], tn = s[2], fn = s[3];
            double prec = tp + fp > 0 ? (double) tp / (tp + fp) : 0;
            double rec = tp + fn > 0 ? (double) tp / (tp + fn) : 0;
            double f1 = prec + rec > 0 ? 2 * prec * rec / (prec + rec) : 0;
            double fpr = fp + tn > 0 ? (double) fp / (fp + tn) : 0;
            System.out.printf("%-18s %9.3f %10.3f %10.3f %10.3f%n",
                    entry.getKey(), prec, rec, f1, fpr);
        }

        // ── Failure details ──
        if (!failures.isEmpty()) {
            System.out.println("\n=== Failures (" + failed + ") ===");
            failures.forEach(System.out::println);
        }

        // ── Verdict ──
        System.out.println("\n=== Verdict ===");
        System.out.printf("Routing Accuracy: %.1f%% (%d/%d)%n", accuracy, passed, total);
        if (accuracy >= 90) {
            System.out.println(
                    "PASS: Layer 0 routing is production-ready.");
        } else if (accuracy >= 75) {
            System.out.println(
                    "WARN: Layer 0 routing needs targeted fixes (see failures above).");
        } else {
            System.out.println(
                    "FAIL: Layer 0 routing accuracy too low. Fix prompt and tool descriptions first.");
        }
    }

    // ── Tool-level confusion matrix updates ──

    private void updateToolStats(Map<String, int[]> stats, String toolName,
                                  Set<String> actual, Set<String> expected,
                                  Set<String> forbidden) {
        int[] s = stats.get(toolName);
        boolean called = actual.contains(toolName);
        boolean shouldCall = expected.contains(toolName);
        boolean shouldNotCall = forbidden.contains(toolName);

        if (called && shouldCall) s[0]++;       // TP
        else if (called && shouldNotCall) s[1]++;// FP
        else if (!called && !shouldCall && !shouldNotCall) s[2]++; // TN
        else if (!called && shouldCall) s[3]++;  // FN
        else if (!called && shouldNotCall) s[2]++;// TN (correctly avoided)
    }

    // ── Helpers ──

    private static String trunc(String s, int max) {
        return s.length() > max ? s.substring(0, max - 1) + "…" : s;
    }

    private static String env(String key, String fallback) {
        String v = System.getenv(key);
        return (v != null && !v.isBlank()) ? v : fallback;
    }
}
