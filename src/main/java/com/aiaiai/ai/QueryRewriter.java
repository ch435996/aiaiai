package com.aiaiai.ai;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class QueryRewriter {

    private static final Logger log = LoggerFactory.getLogger(QueryRewriter.class);

    private static final String SYSTEM_PROMPT = """
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

    private final ChatModel chatModel;

    public QueryRewriter(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public String rewrite(String originalQuery) {
        if (originalQuery == null || originalQuery.isBlank()) {
            return originalQuery;
        }
        try {
            ChatResponse response = chatModel.chat(
                    SystemMessage.from(SYSTEM_PROMPT),
                    UserMessage.from(originalQuery)
            );
            String rewritten = response.aiMessage().text();
            if (rewritten == null || rewritten.isBlank()) {
                log.warn("Query rewrite returned empty, falling back to original: {}", originalQuery);
                return originalQuery;
            }
            log.debug("Query rewritten: '{}' → '{}'", originalQuery, rewritten);
            return rewritten.strip();
        } catch (Exception e) {
            log.warn("Query rewrite failed, falling back to original: '{}'", originalQuery, e);
            return originalQuery;
        }
    }
}
