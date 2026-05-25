package com.aiaiai.eval;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.Test;

import java.time.Duration;

/**
 * Quick rewriter-only probe — no embedding/Pinecone, just shows what the LLM
 * rewrites for key queries so we can verify the prompt change works.
 */
public class RewriterProbe {

    private static final String PROMPT = """
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

    private static final String[][] QUERIES = {
        // SPECIFIC queries — must NOT append overview/survey/comparison
        {"S2", "PoinTr的几何感知transformer和普通transformer有什么区别"},
        {"S1", "SeedFormer的PC-Attn模块输入是什么维度"},
        {"S3", "PCN用了几个MLP做coarse生成"},
        {"S4", "SnowflakeNet每个雪花点有多少个邻居"},
        {"S5", "ProtoComp选了多少个prototype"},
        // METHOD_NAME queries — "overview" queries → can append
        {"H1", "SnowflakeNet 的核心思想"},
        {"H2", "PoinTr 的网络结构"},
        // BROAD queries — should append
        {"B1", "点云领域最新进展"},
        {"B4", "点云补全综述"},
        // CONCEPT queries — should append
        {"M1", "点云补全的损失函数有哪些"},
        {"L2", "点云上采样和补全有什么区别"},
        // MIXED intent — SPECIFIC entities with detail, must NOT append
        {"X4", "SeedFormer的上采样模块怎么设计的，记下来F-score比CD重要"},
        // VAGUE reference — no specific entity, can append
        {"V2", "上次说的那个用diffusion做补全的论文"},
    };

    @Test
    void probe() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) {
            System.out.println("DEEPSEEK_API_KEY not set.");
            return;
        }
        ChatModel model = OpenAiChatModel.builder()
                .apiKey(key).baseUrl("https://api.deepseek.com")
                .modelName("deepseek-chat").temperature(0.0).maxTokens(200)
                .timeout(Duration.ofSeconds(30)).build();

        System.out.println("=== Rewriter Probe (new prompt) ===\n");

        for (String[] q : QUERIES) {
            String rewritten = rewrite(model, q[1]);
            boolean hasOverview = rewritten.toLowerCase().contains("overview")
                    || rewritten.toLowerCase().contains("survey")
                    || rewritten.toLowerCase().contains("comparison");
            String flag = hasOverview ? "[+OVERVIEW]" : "[OK]";
            System.out.printf("%-4s %s%n  raw : %s%n  rew : %s%n%n", q[0], flag, q[1], rewritten);
        }
    }

    private String rewrite(ChatModel model, String query) {
        try {
            var resp = model.chat(SystemMessage.from(PROMPT), UserMessage.from(query));
            String r = resp.aiMessage().text();
            return (r != null && !r.isBlank()) ? r.strip() : query;
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }
}
