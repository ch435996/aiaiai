package com.aiaiai.ai;

import com.aiaiai.memory.MemoryRecallResult;
import com.aiaiai.memory.MemoryService;
import com.aiaiai.retrieval.RetrievalService;
import com.aiaiai.retrieval.SearchResult;
import com.aiaiai.routing.ClassificationResult;
import com.aiaiai.routing.IntentClassificationHolder;
import com.aiaiai.routing.QueryIntent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.service.MemoryId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class AiaiaiTools {

    private static final Logger log = LoggerFactory.getLogger(AiaiaiTools.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final RetrievalService retrievalService;
    private final MemoryService memoryService;
    private final QueryRewriter queryRewriter;
    private final IntentClassificationHolder intentHolder;

    public AiaiaiTools(RetrievalService retrievalService, MemoryService memoryService,
                       QueryRewriter queryRewriter,
                       IntentClassificationHolder intentHolder) {
        this.retrievalService = retrievalService;
        this.memoryService = memoryService;
        this.queryRewriter = queryRewriter;
        this.intentHolder = intentHolder;
    }

    @Tool("检索三维重建/点云补全知识库。当用户询问论文方法、网络结构、损失函数、训练策略、"
            + "数据集、指标、实验结论或方法对比时调用。不可用于闲聊或偏好记忆写入。")
    public String searchKnowledge(
            @P(value = "检索查询词。归纳对比类问题（有哪些/对比/区别/优缺点/分类）使用综述关键词"
                    + "(survey/comparison/taxonomy/overview)搭配具体方法名；细节类问题使用精确方法名和模块名。"
                    + "禁止只输入单一方法名——即使是定向问题也要包含任务描述", required = true)
            String query,
            @MemoryId String sessionId) {
        if (query == null || query.isBlank()) return "错误：检索查询为空，请提供有效的查询词";
        ClassificationResult result = intentHolder.get(sessionId);
        intentHolder.invalidate(sessionId);
        QueryIntent intent = result.intent();
        QueryIntent.PipelineConfig cfg = intent.config();

        String searchQuery = cfg.rewrite() ? queryRewriter.rewrite(query) : query;
        log.debug("searchKnowledge intent={} confidence={} rewrite={} topK={} rerank={}",
                intent.name(), result.confidence(), cfg.rewrite(), cfg.topK(), cfg.rerank());

        SearchResult sr = searchWithRetry(searchQuery, cfg);
        if (cfg.rerank() && sr.count() > 0) {
            sr = retrievalService.rerankStructured(sr, searchQuery, cfg.finalTopK());
        }
        return toSearchResultJson(sr);
    }

    private SearchResult searchWithRetry(String searchQuery, QueryIntent.PipelineConfig cfg) {
        try {
            return retrievalService.searchStructured(searchQuery, cfg.topK());
        } catch (Exception e) {
            log.warn("searchKnowledge first attempt failed: {}, retrying once", e.getMessage());
            try {
                return retrievalService.searchStructured(searchQuery, cfg.topK());
            } catch (Exception e2) {
                log.error("searchKnowledge retry also failed: {}", e2.getMessage());
                return new SearchResult(List.of(), 0, 0);
            }
        }
    }

    private String toSearchResultJson(SearchResult sr) {
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
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize search result", e);
            return "错误：检索结果序列化失败";
        }
    }

    @Tool("将信息保存到长期记忆。仅在用户明确要求记住某事、透露课题组稳定偏好"
            + "或长期有效的研究约束时调用。不可保存临时情绪或一次性指令。")
    public String saveMemory(
            @P(value = "需要保存的记忆内容，完整陈述句", required = true)
            String content) {
        if (content == null || content.isBlank()) {
            return "{\"status\": \"error\", \"message\": \"记忆内容为空，无法保存\"}";
        }
        memoryService.save(content);
        try {
            return objectMapper.writeValueAsString(
                    Map.of("status", "saved", "content", content));
        } catch (JsonProcessingException e) {
            return "{\"status\": \"saved\"}";
        }
    }

    @Tool("从长期记忆中召回相关信息。当用户询问的内容可能涉及之前存储的偏好、"
            + "课题组规范、研究方向等记忆时调用。")
    public String recallMemory(
            @P(value = "用于搜索长期记忆的查询词", required = true)
            String query) {
        if (query == null || query.isBlank()) {
            return "{\"count\": 0, \"memories\": []}";
        }
        String rewritten = queryRewriter.rewrite(query);
        MemoryRecallResult result = memoryService.recallStructured(rewritten);
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize recall result", e);
            return "{\"count\": 0, \"memories\": []}";
        }
    }
}
