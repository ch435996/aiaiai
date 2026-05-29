package com.aiaiai.orchestrator;

import com.aiaiai.ai.AiaiaiTools;
import com.aiaiai.ai.Assistant;
import com.aiaiai.ai.StreamingAssistant;
import com.aiaiai.controller.dto.MessageDto;
import com.aiaiai.controller.dto.RetrievalSnippet;
import com.aiaiai.controller.dto.ToolCallDto;
import com.aiaiai.memory.MemoryService;
import com.aiaiai.memory.HybridChatMemoryStore;
import com.aiaiai.retrieval.RetrievalService;
import com.aiaiai.retrieval.SearchResult;
import com.aiaiai.routing.ClassificationResult;
import com.aiaiai.routing.IntentClassificationHolder;
import com.aiaiai.routing.IntentClassifier;
import com.aiaiai.routing.QueryIntent;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class ChatOrchestrator {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final HybridChatMemoryStore memoryStore;
    private final MemoryService memoryService;
    private final RetrievalService retrievalService;
    private final AiaiaiTools tools;
    private final IntentClassifier intentClassifier;
    private final IntentClassificationHolder intentHolder;
    private final int maxMessages;
    private final Assistant assistant;
    private final StreamingAssistant streamingAssistant;

    public ChatOrchestrator(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            HybridChatMemoryStore memoryStore,
            AiaiaiTools tools,
            MemoryService memoryService,
            RetrievalService retrievalService,
            IntentClassifier intentClassifier,
            IntentClassificationHolder intentHolder,
            @Value("${aiaiai.memory.short-term.max-messages}") int maxMessages) {
        this.memoryStore = memoryStore;
        this.memoryService = memoryService;
        this.retrievalService = retrievalService;
        this.tools = tools;
        this.intentClassifier = intentClassifier;
        this.intentHolder = intentHolder;
        this.maxMessages = maxMessages;
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(maxMessages)
                        .chatMemoryStore(memoryStore)
                        .build())
                .tools(tools)
                .maxSequentialToolsInvocations(5)
                .systemMessageProvider(sid -> systemPrompt())
                .build();
        this.streamingAssistant = AiServices.builder(StreamingAssistant.class)
                .streamingChatModel(streamingChatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(maxMessages)
                        .chatMemoryStore(memoryStore)
                        .build())
                .tools(tools)
                .maxSequentialToolsInvocations(5)
                .systemMessageProvider(sid -> systemPrompt())
                .build();
    }

    public ProcessResult processMessage(String sessionId, String message) {
        List<ChatMessage> history = memoryStore.getMessages(sessionId);
        int beforeCount = history.size();
        message = injectLongTermMemory(sessionId, message);

        ClassificationResult result = intentClassifier.classify(message, history);
        intentHolder.put(sessionId, result);
        log.info("Session {} intent={} confidence={} source={}",
                sessionId, result.intent().name(), result.confidence(), result.source());

        try {
            String reply = assistant.chat(sessionId, message);
            List<ToolCallDto> toolCalls = extractToolCalls(
                    memoryStore.getMessages(sessionId), beforeCount);
            return new ProcessResult(reply, toolCalls);
        } finally {
            intentHolder.invalidate(sessionId);
        }
    }

    /** 返回 SseEmitter，调用方需持有返回值以保持 SSE 连接 */
    public SseEmitter processMessageStreaming(String sessionId, String message) {
        List<ChatMessage> history = memoryStore.getMessages(sessionId);
        int beforeCount = history.size();
        String augmentedMessage = injectLongTermMemory(sessionId, message);

        ClassificationResult result = intentClassifier.classify(augmentedMessage, history);
        intentHolder.put(sessionId, result);
        log.info("Session {} intent={} confidence={} source={}",
                sessionId, result.intent().name(), result.confidence(), result.source());

        SseEmitter emitter = new SseEmitter(300_000L);

        emitter.onCompletion(() -> intentHolder.invalidate(sessionId));
        emitter.onTimeout(() -> intentHolder.invalidate(sessionId));

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("sessionId", sessionId)));
        } catch (IOException e) {
            log.warn("Failed to send connected event for session {}", sessionId, e);
            intentHolder.invalidate(sessionId);
            return emitter;
        }

        TokenStream tokenStream;
        try {
            tokenStream = streamingAssistant.chat(sessionId, augmentedMessage);
        } catch (Exception e) {
            log.error("Failed to create token stream for session {}", sessionId, e);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("message", "创建流式会话失败: " + e.getMessage())));
            } catch (IOException ignored) {}
            emitter.completeWithError(e);
            intentHolder.invalidate(sessionId);
            return emitter;
        }

        tokenStream.onPartialResponse(token -> {
            log.debug("Session {} token: {}", sessionId, token.substring(0, Math.min(token.length(), 50)));
            try {
                emitter.send(SseEmitter.event()
                        .name("token")
                        .data(Map.of("content", token)));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        tokenStream.beforeToolExecution(before -> {
            var req = before.request();
            log.info("Session {} tool call start: {}", sessionId, req.name());
            try {
                emitter.send(SseEmitter.event()
                        .name("tool_call")
                        .data(toSseMap(buildRequestDto(req))));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        tokenStream.onToolExecuted(exec -> {
            log.info("Session {} tool executed: {} (failed={})", sessionId,
                    exec.request().name(), exec.hasFailed());
            if (exec.hasFailed()) {
                log.error("Session {} tool {} failed: {}", sessionId,
                        exec.request().name(), exec.result());
            }
            try {
                emitter.send(SseEmitter.event()
                        .name("tool_result")
                        .data(toSseMap(buildResultDto(exec.request().name(), exec.result()))));
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
        });

        tokenStream.onCompleteResponse(response -> {
            log.info("Session {} stream complete, finishReason={}, tokenUsage={}",
                    sessionId,
                    response.finishReason(),
                    response.tokenUsage());
            List<ToolCallDto> toolCalls = extractToolCalls(
                    memoryStore.getMessages(sessionId), beforeCount);
            try {
                emitter.send(SseEmitter.event()
                        .name("done")
                        .data(Map.of(
                                "sessionId", sessionId,
                                "toolCalls", toolCalls
                        )));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            intentHolder.invalidate(sessionId);
        });

        tokenStream.onError(throwable -> {
            log.error("Session {} stream error", sessionId, throwable);
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data(Map.of("message", throwable.getMessage())));
            } catch (IOException ignored) {
            }
            emitter.completeWithError(throwable);
            intentHolder.invalidate(sessionId);
        });

        log.info("Starting stream for session {}", sessionId);
        tokenStream.start();
        return emitter;
    }

    // ── Tool call DTO builders ──

    private ToolCallDto buildRequestDto(ToolExecutionRequest req) {
        ToolCallDto dto = new ToolCallDto();
        dto.setToolName(req.name());
        dto.setStatus("request");
        dto.setSummary(summarizeToolInput(req.name()));
        dto.setInputPreview(extractInputPreview(req));
        return dto;
    }

    private ToolCallDto buildResultDto(String toolName, String resultText) {
        ToolCallDto dto = new ToolCallDto();
        dto.setToolName(toolName);
        dto.setStatus("result");
        dto.setSummary(summarizeToolResultLabel(toolName, resultText));

        if ("searchKnowledge".equals(toolName) && resultText != null) {
            List<RetrievalSnippet> snippets = extractSnippetsFromResult(resultText);
            dto.setSnippets(snippets);
            dto.setResultCount(snippets.size());
            if (!snippets.isEmpty()) {
                dto.setResultSummary(String.format("命中 %d 条，最高置信度 %s",
                        snippets.size(), snippets.get(0).getConfidence()));
            } else {
                dto.setResultSummary("未找到相关内容");
            }
        } else if ("saveMemory".equals(toolName) && resultText != null) {
            dto.setResultSummary(parseSaveMemoryStatus(resultText));
        } else if ("recallMemory".equals(toolName) && resultText != null) {
            parseRecallMemoryResult(dto, resultText);
        } else {
            dto.setResultSummary(dto.getSummary());
        }
        return dto;
    }

    // ── SSE event helper ──

    /** 只传输非 null 的字段，减少 JSON 体积 */
    private Map<String, Object> toSseMap(ToolCallDto dto) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("toolName", dto.getToolName());
        map.put("status", dto.getStatus());
        if (dto.getSummary() != null) map.put("summary", dto.getSummary());
        if (dto.getInputPreview() != null) map.put("inputPreview", dto.getInputPreview());
        if (dto.getResultSummary() != null) map.put("resultSummary", dto.getResultSummary());
        if (dto.getResultCount() > 0) map.put("resultCount", dto.getResultCount());
        if (dto.getSnippets() != null && !dto.getSnippets().isEmpty())
            map.put("snippets", dto.getSnippets());
        return map;
    }

    // ── Input extraction ──

    /** 从 ToolExecutionRequest.arguments() JSON 中提取用户可见的预览 */
    private String extractInputPreview(ToolExecutionRequest req) {
        String args = req.arguments();
        if (args == null || args.isBlank()) return null;
        try {
            Map<String, Object> map = objectMapper.readValue(args,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            // 依次尝试 query / content 参数
            Object val = map.getOrDefault("query", map.get("content"));
            if (val == null) return null;
            String text = val.toString();
            return text.length() > 80 ? text.substring(0, 80) + "…" : text;
        } catch (Exception e) {
            log.debug("Failed to parse tool arguments JSON: {}", args, e);
            return null;
        }
    }

    /** 优先从 JSON 解析检索结果片段，解析失败则回退到正则提取 */
    private List<RetrievalSnippet> extractSnippetsFromResult(String resultText) {
        try {
            int jsonStart = resultText.indexOf('{');
            if (jsonStart >= 0) {
                int jsonEnd = resultText.lastIndexOf('}') + 1;
                String jsonPart = resultText.substring(jsonStart, jsonEnd);
                Map<String, Object> map = objectMapper.readValue(jsonPart,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> rawSnippets = (List<Map<String, Object>>) map.get("snippets");
                if (rawSnippets != null) {
                    List<RetrievalSnippet> snippets = new ArrayList<>();
                    for (int i = 0; i < rawSnippets.size() && i < 5; i++) {
                        Map<String, Object> s = rawSnippets.get(i);
                        double score = s.get("score") instanceof Number n ? n.doubleValue() : 0;
                        String confidence = String.valueOf(s.getOrDefault("confidence", "LOW"));
                        String text = String.valueOf(s.getOrDefault("text", ""));
                        String section = String.valueOf(s.getOrDefault("section", "知识库"));
                        String preview = text.length() > 120 ? text.substring(0, 120) + "…" : text;
                        snippets.add(new RetrievalSnippet(score, confidence, preview,
                                section.isEmpty() || "null".equals(section) ? "知识库" : section));
                    }
                    return snippets;
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse searchKnowledge result as JSON, falling back to regex: {}", e.getMessage());
        }
        return retrievalService.extractSnippets(resultText);
    }

    /** 从 saveMemory 的 JSON 结果中提取状态描述 */
    private String parseSaveMemoryStatus(String resultText) {
        try {
            Map<String, Object> map = objectMapper.readValue(resultText,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            String status = String.valueOf(map.getOrDefault("status", ""));
            if ("saved".equals(status)) {
                String content = String.valueOf(map.getOrDefault("content", ""));
                String preview = content.length() > 40 ? content.substring(0, 40) + "…" : content;
                return "已保存: " + preview;
            }
            return "保存失败: " + map.getOrDefault("message", "未知错误");
        } catch (Exception e) {
            return "已保存";
        }
    }

    /** 从 recallMemory 的 JSON 结果中提取片段和计数 */
    private void parseRecallMemoryResult(ToolCallDto dto, String resultText) {
        try {
            Map<String, Object> map = objectMapper.readValue(resultText,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            int count = map.get("count") instanceof Number n ? n.intValue() : 0;
            dto.setResultCount(count);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> memories = (List<Map<String, Object>>) map.get("memories");
            if (memories != null && !memories.isEmpty()) {
                List<RetrievalSnippet> snippets = new ArrayList<>();
                for (int i = 0; i < memories.size() && i < 5; i++) {
                    Map<String, Object> m = memories.get(i);
                    double score = m.get("score") instanceof Number n ? n.doubleValue() : 0;
                    String text = String.valueOf(m.getOrDefault("text", ""));
                    String preview = text.length() > 120 ? text.substring(0, 120) + "…" : text;
                    snippets.add(new RetrievalSnippet(score, "MEDIUM", preview, "长期记忆"));
                }
                dto.setSnippets(snippets);
                dto.setResultSummary(String.format("召回 %d 条记忆", count));
            } else {
                dto.setResultSummary("未找到相关记忆");
            }
        } catch (Exception e) {
            log.debug("Failed to parse recallMemory result as JSON, falling back", e);
            List<RetrievalSnippet> snippets = retrievalService.extractSnippets(resultText);
            dto.setSnippets(snippets);
            dto.setResultCount(snippets.size());
            dto.setResultSummary(snippets.isEmpty() ? "未找到相关记忆"
                    : String.format("召回 %d 条记忆", snippets.size()));
        }
    }

    // ── Summary helpers (label only, used by summary + backward compat) ──

    private String summarizeToolInput(String toolName) {
        return switch (toolName) {
            case "searchKnowledge" -> "检索知识库";
            case "saveMemory" -> "保存长期记忆";
            case "recallMemory" -> "召回长期记忆";
            default -> toolName;
        };
    }

    private String summarizeToolResultLabel(String toolName, String resultText) {
        return switch (toolName) {
            case "searchKnowledge" -> {
                int count = countMatches(resultText, "--- 结果");
                yield count > 0 ? "检索到 " + count + " 条结果" : "检索完毕";
            }
            case "saveMemory" -> {
                if (resultText != null && resultText.contains("\"saved\"")) yield "已保存";
                yield "保存完毕";
            }
            case "recallMemory" -> {
                if (resultText == null) yield "未找到相关记忆";
                if (resultText.contains("\"count\":0")) yield "未找到相关记忆";
                if (resultText.contains("\"count\":")) yield "召回完毕";
                if (resultText.contains("未找到")) yield "未找到相关记忆";
                int count = countMatches(resultText, "--- 记忆");
                yield count > 0 ? "召回 " + count + " 条记忆" : "召回完毕";
            }
            default -> "完成";
        };
    }

    // ── Post-hoc extraction from chat memory (for both sync path and SSE done event) ──

    private List<ToolCallDto> extractToolCalls(List<ChatMessage> messages, int fromIndex) {
        List<ToolCallDto> calls = new ArrayList<>();
        // 累积本轮出现的 ToolExecutionRequest，用于 result 阶段配对
        ToolCallDto pendingRequest = null;

        for (int i = fromIndex; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof AiMessage aiMsg && aiMsg.hasToolExecutionRequests()) {
                for (ToolExecutionRequest req : aiMsg.toolExecutionRequests()) {
                    pendingRequest = buildRequestDto(req);
                    calls.add(pendingRequest);
                }
            } else if (msg instanceof ToolExecutionResultMessage res) {
                ToolCallDto resultDto = buildResultDto(res.toolName(), res.text());
                calls.add(resultDto);
            }
        }
        return calls;
    }

    private int countMatches(String text, String pattern) {
        if (text == null) return 0;
        int count = 0, idx = 0;
        while ((idx = text.indexOf(pattern, idx)) != -1) {
            count++;
            idx += pattern.length();
        }
        return count;
    }

    private String injectLongTermMemory(String sessionId, String message) {
        if (memoryStore.getMessages(sessionId).isEmpty()) {
            String recalled = memoryService.recall("课题组偏好 研究方向 实验规范 数据集 评价指标");
            if (recalled != null && !recalled.contains("未找到相关记忆")) {
                return message + "\n\n[系统注入的长期记忆上下文，请参考其中与本问题相关的偏好和规范]\n" + recalled;
            }
        }
        return message;
    }

    public void endSession(String sessionId) {
        memoryStore.deleteMessages(sessionId);
    }

    public List<MessageDto> getMessageHistory(String sessionId) {
        return memoryStore.getMessages(sessionId).stream()
                .map(ChatOrchestrator::toDto)
                .toList();
    }

    private static MessageDto toDto(ChatMessage msg) {
        String role;
        String content;
        if (msg instanceof UserMessage um) {
            role = "user";
            content = um.singleText();
        } else if (msg instanceof AiMessage am) {
            role = "assistant";
            content = am.text() != null ? am.text() : "";
        } else if (msg instanceof SystemMessage sm) {
            role = "system";
            content = sm.text();
        } else if (msg instanceof ToolExecutionResultMessage trm) {
            role = "tool";
            content = trm.text();
        } else {
            role = "unknown";
            content = msg.toString();
        }
        return new MessageDto(role, content);
    }

    private String systemPrompt() {
        return """
                你是三维重建/点云补全领域的智能科研助手。

                工具使用指南：
                - searchKnowledge：仅在用户询问三维重建/点云补全领域的论文事实、方法细节、实验结果、
                  数据集或指标时调用。检索结果以 JSON 格式返回，包含 snippets 数组，
                  每条含 index（引用编号）/ score（相关度）/ confidence（置信度）/ section（章节）/ text（内容）字段。
                  用户问题明显超出此范围时，严禁调用 searchKnowledge，直接简要回答并提示领域边界。
                - saveMemory：仅在用户明确要求"记住"或透露课题组长期稳定偏好时调用。
                  返回 JSON：{"status":"saved","content":"..."}
                - recallMemory：在对话开始时以及用户问题涉及课题组偏好、历史规范时调用。
                  返回 JSON：{"count":N,"memories":[{"index":N,"score":0.X,"text":"..."}]}
                - 闲聊、寒暄或你能自信回答的问题，直接回复即可

                核心回答规则（必须严格遵守）：

                一、来源限定规则
                你只能使用[知识库检索结果]中提供的证据来回答。严禁使用训练时学到的任何论文细节、
                数值、方法描述——即使你确信自己知道，如果检索结果中没有，就不能当作事实来陈述。

                二、引用规则
                每个事实性陈述之后，必须标注其所依据的检索结果编号。格式：
                > 来源：[引用来源 #N]
                其中 N 对应检索结果 JSON 中 snippet 的 index 字段。多个事实必须逐个标注引用，不得在末尾集中标注。
                即使只有一个事实，也必须标注引用来源。

                三、不确定规则（以下任一情况触发，禁止编造答案）
                - 检索结果中不包含问题的直接答案
                - 检索结果提到了相关论文但没有所需的具体细节（如具体数值、参数、配置）
                - 检索结果为空或全部为 LOW 置信度
                触发时使用此模板：
                "根据目前知识库中的资料，暂时无法回答这个问题。[说明缺少的具体信息]。"
                使用此模板时，你的回答必须以句号结束，不得追加任何其他内容。
                禁止追加推测、补充说明、"可能""通常""估计"等任何措辞。

                特别规则——数值查询：
                当用户询问具体数值（维度、F-score、batch size、GPU 数量、学习率、epoch 数等），
                你必须逐一检查每个检索结果中是否明确写有该数值。如果所有检索结果都只有
                方法描述而没有给出精确数值，即使它们讨论了相关方法或模块，也必须使用不确定模板。
                严禁基于"常见配置"或"典型取值"等推理进行推测。

                四、领域边界规则
                用户问题超出三维重建/点云补全领域时，简短回答并严禁调用 searchKnowledge：
                "这是[具体领域]的内容，不在本课题组的论文知识库范围内。建议查阅该领域专业资料。"

                五、证据不足模板
                当检索结果不足以完整回答时：
                "根据目前知识库中的资料，暂时无法完整回答这个问题。以下是我基于现有信息能提供的部分——"
                之后仅回答有检索依据的部分，且必须标注引用来源。

                六、置信度标签
                HIGH=高度相关可放心引用，MEDIUM=中度相关可参考，LOW=弱相关仅供参考不可作为核心论据。
                """;
    }
}
