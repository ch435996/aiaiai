package com.aiaiai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final int ttlDays;

    public RedisChatMemoryStore(RedisTemplate<String, String> redisTemplate, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlDays = 7;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = redisTemplate.opsForValue().get(key(memoryId));
        if (json == null || json.isEmpty()) {
            return List.of();
        }
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = key(memoryId);
        String json = ChatMessageSerializer.messagesToJson(messages);
        redisTemplate.opsForValue().set(key, json, ttlDays, TimeUnit.DAYS);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(key(memoryId));
        redisTemplate.delete(sessionMetaKey(memoryId.toString()));
        redisTemplate.opsForZSet().remove("chat:sessions", memoryId.toString());
    }

    public void trackSession(String sessionId, String firstMessage) {
        redisTemplate.opsForZSet().add("chat:sessions", sessionId, System.currentTimeMillis());
        try {
            String meta = objectMapper.writeValueAsString(Map.of(
                "sessionId", sessionId,
                "createdAt", System.currentTimeMillis(),
                "preview", firstMessage.length() > 50 ? firstMessage.substring(0, 50) + "…" : firstMessage,
                "messageCount", 1
            ));
            redisTemplate.opsForValue().set(sessionMetaKey(sessionId), meta, ttlDays, TimeUnit.DAYS);
        } catch (JsonProcessingException ignored) {
        }
    }

    public List<Map<String, Object>> listSessions() {
        Set<String> ids = redisTemplate.opsForZSet()
                .reverseRange("chat:sessions", 0, 19);
        if (ids == null || ids.isEmpty()) return List.of();

        List<Map<String, Object>> sessions = new ArrayList<>();
        for (String id : ids) {
            String metaJson = redisTemplate.opsForValue().get(sessionMetaKey(id));
            if (metaJson != null) {
                try {
                    Map<String, Object> meta = objectMapper.readValue(metaJson, Map.class);
                    // 用实际存储的消息数修正 messageCount
                    List<ChatMessage> messages = getMessages(id);
                    meta.put("messageCount", messages.size());
                    sessions.add(meta);
                } catch (JsonProcessingException ignored) {
                }
            }
        }
        return sessions;
    }

    private String key(Object memoryId) {
        return "chat:memory:" + memoryId;
    }

    private String sessionMetaKey(String sessionId) {
        return "chat:session:meta:" + sessionId;
    }
}
