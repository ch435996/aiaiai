package com.aiaiai.ai;

import com.aiaiai.memory.MemoryService;
import com.aiaiai.retrieval.RetrievalService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

@Component
public class AiaiaiTools {

    private final RetrievalService retrievalService;
    private final MemoryService memoryService;

    public AiaiaiTools(RetrievalService retrievalService, MemoryService memoryService) {
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
    }

    @Tool("检索三维重建/点云补全知识库。当用户询问论文方法、网络结构、损失函数、训练策略、"
            + "数据集、指标、实验结论或方法对比时调用。不可用于闲聊或偏好记忆写入。")
    public String searchKnowledge(
            @P(value = "检索查询词，提取用户问题中的关键概念、方法名或任务描述", required = true)
            String query) {
        return retrievalService.search(query);
    }

    @Tool("将信息保存到长期记忆。仅在用户明确要求记住某事、透露课题组稳定偏好"
            + "或长期有效的研究约束时调用。不可保存临时情绪或一次性指令。")
    public String saveMemory(
            @P(value = "需要保存的记忆内容，完整陈述句", required = true)
            String content) {
        memoryService.save(content);
        return "已保存到长期记忆: " + content;
    }

    @Tool("从长期记忆中召回相关信息。当用户询问的内容可能涉及之前存储的偏好、"
            + "课题组规范、研究方向等记忆时调用。")
    public String recallMemory(
            @P(value = "用于搜索长期记忆的查询词", required = true)
            String query) {
        return memoryService.recall(query);
    }
}
