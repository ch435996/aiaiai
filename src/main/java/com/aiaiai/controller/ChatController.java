package com.aiaiai.controller;

import com.aiaiai.controller.dto.ChatRequest;
import com.aiaiai.controller.dto.ChatResponse;
import com.aiaiai.controller.dto.MessageDto;
import com.aiaiai.memory.HybridChatMemoryStore;
import com.aiaiai.orchestrator.ChatOrchestrator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class ChatController {

    private final ChatOrchestrator orchestrator;
    private final HybridChatMemoryStore memoryStore;

    public ChatController(ChatOrchestrator orchestrator, HybridChatMemoryStore memoryStore) {
        this.orchestrator = orchestrator;
        this.memoryStore = memoryStore;
    }

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId();
        boolean isNew = (sessionId == null || sessionId.isBlank());
        if (isNew) {
            sessionId = UUID.randomUUID().toString();
        }
        var result = orchestrator.processMessage(sessionId, request.getMessage());
        if (isNew) {
            memoryStore.trackSession(sessionId, request.getMessage());
        }
        return ResponseEntity.ok(new ChatResponse(sessionId, result.getReply(), result.getToolCalls()));
    }

    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {
        String sessionId = request.getSessionId();
        boolean isNew = (sessionId == null || sessionId.isBlank());
        if (isNew) {
            sessionId = UUID.randomUUID().toString();
        }
        SseEmitter emitter = orchestrator.processMessageStreaming(sessionId, request.getMessage());
        if (isNew) {
            memoryStore.trackSession(sessionId, request.getMessage());
        }
        return emitter;
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        return ResponseEntity.ok(memoryStore.listSessions());
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<List<MessageDto>> getSessionMessages(@PathVariable String sessionId) {
        return ResponseEntity.ok(orchestrator.getMessageHistory(sessionId));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        orchestrator.endSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}
