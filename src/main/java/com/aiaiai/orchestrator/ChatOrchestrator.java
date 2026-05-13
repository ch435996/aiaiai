package com.aiaiai.orchestrator;

import com.aiaiai.ai.AiaiaiTools;
import com.aiaiai.ai.Assistant;
import com.aiaiai.ai.StreamingAssistant;
import com.aiaiai.controller.dto.MessageDto;
import com.aiaiai.controller.dto.RetrievalSnippet;
import com.aiaiai.controller.dto.ToolCallDto;
import com.aiaiai.memory.MemoryService;
import com.aiaiai.memory.RedisChatMemoryStore;
import com.aiaiai.retrieval.RetrievalService;
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

    private final RedisChatMemoryStore memoryStore;
    private final MemoryService memoryService;
    private final RetrievalService retrievalService;
    private final AiaiaiTools tools;
    private final int maxMessages;
    private final Assistant assistant;
    private final StreamingAssistant streamingAssistant;

    public ChatOrchestrator(
            ChatModel chatModel,
            StreamingChatModel streamingChatModel,
            RedisChatMemoryStore memoryStore,
            AiaiaiTools tools,
            MemoryService memoryService,
            RetrievalService retrievalService,
            @Value("${aiaiai.memory.short-term.max-messages}") int maxMessages) {
        this.memoryStore = memoryStore;
        this.memoryService = memoryService;
        this.retrievalService = retrievalService;
        this.tools = tools;
        this.maxMessages = maxMessages;
        this.assistant = AiServices.builder(Assistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(maxMessages)
                        .chatMemoryStore(memoryStore)
                        .build())
                .tools(tools)
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
                .systemMessageProvider(sid -> systemPrompt())
                .build();
    }

    public ProcessResult processMessage(String sessionId, String message) {
        int beforeCount = memoryStore.getMessages(sessionId).size();
        message = injectLongTermMemory(sessionId, message);
        String reply = assistant.chat(sessionId, message);
        List<ToolCallDto> toolCalls = extractToolCalls(
                memoryStore.getMessages(sessionId), beforeCount);
        return new ProcessResult(reply, toolCalls);
    }

    /** 返回 SseEmitter，调用方需持有返回值以保持 SSE 连接 */
    public SseEmitter processMessageStreaming(String sessionId, String message) {
        int beforeCount = memoryStore.getMessages(sessionId).size();
        String augmentedMessage = injectLongTermMemory(sessionId, message);

        SseEmitter emitter = new SseEmitter(300_000L);

        try {
            emitter.send(SseEmitter.event()
                    .name("connected")
                    .data(Map.of("sessionId", sessionId)));
        } catch (IOException e) {
            log.warn("Failed to send connected event for session {}", sessionId, e);
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
            List<RetrievalSnippet> snippets = retrievalService.extractSnippets(resultText);
            dto.setSnippets(snippets);
            dto.setResultCount(snippets.size());
            if (!snippets.isEmpty()) {
                dto.setResultSummary(String.format("命中 %d 条，最高置信度 %s",
                        snippets.size(), snippets.get(0).getConfidence()));
            } else {
                dto.setResultSummary("未找到相关内容");
            }
        } else if ("recallMemory".equals(toolName) && resultText != null) {
            List<RetrievalSnippet> snippets = retrievalService.extractSnippets(resultText);
            dto.setSnippets(snippets);
            dto.setResultCount(snippets.size());
            if (!snippets.isEmpty()) {
                dto.setResultSummary(String.format("召回 %d 条记忆", snippets.size()));
            } else {
                dto.setResultSummary("未找到相关记忆");
            }
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
            case "saveMemory" -> "已保存";
            case "recallMemory" -> {
                if (resultText == null || resultText.contains("未找到"))
                    yield "未找到相关记忆";
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
                - searchKnowledge：仅在用户询问论文事实、方法细节、实验结果、数据集或指标时调用
                - saveMemory：仅在用户明确要求"记住"或透露课题组长期稳定偏好时调用
                - recallMemory：在对话开始时以及用户问题涉及课题组偏好、历史规范时调用，检查是否有相关长期记忆
                - 闲聊、寒暄或你能自信回答的问题，直接回复即可

                回答原则：
                - 优先使用知识库证据，证据不足时明确说明"不确定"
                - 涉及论文细节和实验配置时，先检索再回答
                - 超出三维重建/点云补全领域的问题可简要回答，但需提示领域边界
                - 置信度标签含义：HIGH=高度相关可放心引用，MEDIUM=中度相关可参考，LOW=弱相关仅供参考不可作为核心论据
                - 当检索结果不足以完整回答时，先说明"根据目前知识库中的资料，暂时无法完整回答这个问题。以下是我基于现有信息能提供的部分——"，再仅回答有依据的部分
                - 回答末尾标注引用来源，格式："> 来源：知识库检索结果 #N"
                """;
    }
}
