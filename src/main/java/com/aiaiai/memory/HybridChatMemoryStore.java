package com.aiaiai.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class HybridChatMemoryStore implements ChatMemoryStore {

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    private final int ttlDays;
    private final int maxMessages;
    private final Path sessionDataDir;

    // sessionId -> set of message JSON hashes already written to JSONL file
    private final ConcurrentHashMap<String, Set<Integer>> knownHashes = new ConcurrentHashMap<>();
    // per-session write locks for file append
    private final ConcurrentHashMap<String, Object> fileLocks = new ConcurrentHashMap<>();

    public HybridChatMemoryStore(
            RedisTemplate<String, String> redisTemplate,
            ObjectMapper objectMapper,
            @Value("${aiaiai.memory.short-term.ttl-days:7}") int ttlDays,
            @Value("${aiaiai.memory.short-term.max-messages:20}") int maxMessages,
            @Value("${aiaiai.memory.short-term.session-data-dir:data/sessions}") String sessionDataDirPath) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.ttlDays = ttlDays;
        this.maxMessages = maxMessages;
        this.sessionDataDir = Path.of(sessionDataDirPath);
        ensureDataDir();
    }

    private void ensureDataDir() {
        try {
            Files.createDirectories(sessionDataDir);
        } catch (IOException e) {
            log.error("Failed to create session data directory: {}", sessionDataDir, e);
        }
    }

    // ── ChatMemoryStore interface ──

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String sid = memoryId.toString();
        try {
            String json = redisTemplate.opsForValue().get(key(sid));
            if (json != null && !json.isEmpty()) {
                return ChatMessageDeserializer.messagesFromJson(json);
            }
        } catch (Exception e) {
            log.warn("Redis getMessages failed for session {}, falling back to file", sid, e);
        }
        return rebuildFromFile(sid);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String sid = memoryId.toString();

        // L1 hot: Redis window overwrite (best-effort)
        try {
            String json = ChatMessageSerializer.messagesToJson(messages);
            redisTemplate.opsForValue().set(key(sid), json, ttlDays, TimeUnit.DAYS);
        } catch (Exception e) {
            log.warn("Redis updateMessages failed for session {}, file layer continues", sid, e);
        }

        // L2 warm: append new messages to JSONL file
        Set<Integer> hashes = knownHashes.computeIfAbsent(sid, k -> {
            Set<Integer> set = ConcurrentHashMap.newKeySet();
            rebuildHashesFromFile(sid, set);
            return set;
        });

        List<String> newLines = new ArrayList<>();
        for (ChatMessage msg : messages) {
            String msgJson = ChatMessageSerializer.messageToJson(msg);
            int hash = msgJson.hashCode();
            if (hashes.add(hash)) {
                newLines.add(msgJson);
            }
        }

        if (!newLines.isEmpty()) {
            appendToFile(sid, newLines);
        }
    }

    @Override
    public void deleteMessages(Object memoryId) {
        String sid = memoryId.toString();
        redisTemplate.delete(key(sid));
        redisTemplate.delete(sessionMetaKey(sid));
        redisTemplate.opsForZSet().remove("chat:sessions", sid);
        knownHashes.remove(sid);
        fileLocks.remove(sid);
        try {
            Files.deleteIfExists(sessionFile(sid));
        } catch (IOException e) {
            log.warn("Failed to delete session file for {}", sid, e);
        }
    }

    // ── Session management ──

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
        // Redis primary path
        Set<String> ids = redisTemplate.opsForZSet().reverseRange("chat:sessions", 0, 19);
        if (ids != null && !ids.isEmpty()) {
            List<Map<String, Object>> sessions = new ArrayList<>();
            for (String id : ids) {
                Map<String, Object> meta = loadSessionMeta(id);
                if (meta != null) {
                    sessions.add(meta);
                }
            }
            if (!sessions.isEmpty()) {
                return sessions;
            }
        }
        // File fallback: scan session directory
        return listSessionsFromFiles();
    }

    // ── Redis keys ──

    private String key(Object memoryId) {
        return "chat:memory:" + memoryId;
    }

    private String key(String sessionId) {
        return "chat:memory:" + sessionId;
    }

    private String sessionMetaKey(String sessionId) {
        return "chat:session:meta:" + sessionId;
    }

    // ── File I/O ──

    private Path sessionFile(String sessionId) {
        // sanitize: replace path separators
        String safe = sessionId.replaceAll("[\\\\/:*?\"<>|]", "_");
        return sessionDataDir.resolve(safe + ".jsonl");
    }

    private void appendToFile(String sessionId, List<String> lines) {
        Object lock = fileLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            try {
                Path file = sessionFile(sessionId);
                // join lines with newline, add trailing newline
                String content = String.join("\n", lines) + "\n";
                Files.write(file, content.getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.error("Failed to append messages to file for session {}: {} lines lost",
                        sessionId, lines.size(), e);
            }
        }
    }

    private void rebuildHashesFromFile(String sessionId, Set<Integer> target) {
        Path file = sessionFile(sessionId);
        if (!Files.exists(file)) return;
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.isBlank()) {
                    target.add(line.hashCode());
                }
            }
            log.debug("Rebuilt {} hashes from file for session {}", target.size(), sessionId);
        } catch (IOException e) {
            log.warn("Failed to rebuild hashes from file for session {}", sessionId, e);
        }
    }

    private List<ChatMessage> rebuildFromFile(String sessionId) {
        Path file = sessionFile(sessionId);
        if (!Files.exists(file)) return List.of();

        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            // take last maxMessages non-blank lines
            List<String> recentLines = new ArrayList<>();
            for (int i = lines.size() - 1; i >= 0 && recentLines.size() < maxMessages; i--) {
                String line = lines.get(i);
                if (!line.isBlank()) {
                    recentLines.add(0, line); // prepend to preserve order
                }
            }

            List<ChatMessage> messages = new ArrayList<>();
            for (String line : recentLines) {
                // single-message JSON -> wrap in array for deserializer
                ChatMessage msg = ChatMessageDeserializer.messageFromJson(line);
                if (msg != null) {
                    messages.add(msg);
                }
            }
            // restore to Redis to warm the cache
            if (!messages.isEmpty()) {
                String json = ChatMessageSerializer.messagesToJson(messages);
                redisTemplate.opsForValue().set(key(sessionId), json, ttlDays, TimeUnit.DAYS);
            }
            return messages;
        } catch (Exception e) {
            log.error("Failed to rebuild messages from file for session {}", sessionId, e);
            return List.of();
        }
    }

    private List<Map<String, Object>> listSessionsFromFiles() {
        try {
            List<Map<String, Object>> sessions = new ArrayList<>();
            var dir = sessionDataDir;
            if (!Files.exists(dir)) return sessions;

            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.toString().endsWith(".jsonl"))
                        .sorted((a, b) -> Long.compare(
                                b.toFile().lastModified(),
                                a.toFile().lastModified()))
                        .limit(20)
                        .forEach(p -> {
                            String fileName = p.getFileName().toString();
                            String sid = fileName.substring(0, fileName.length() - 6); // strip .jsonl
                            try {
                                List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                                String preview = "";
                                if (!lines.isEmpty()) {
                                    ChatMessage first = ChatMessageDeserializer.messageFromJson(lines.get(0));
                                    if (first != null) {
                                        String text = first.toString();
                                        preview = text.length() > 50 ? text.substring(0, 50) + "…" : text;
                                    }
                                }
                                sessions.add(Map.of(
                                        "sessionId", sid,
                                        "createdAt", p.toFile().lastModified(),
                                        "preview", preview,
                                        "messageCount", lines.size()
                                ));
                            } catch (Exception ignored) {
                            }
                        });
            }
            return sessions;
        } catch (IOException e) {
            log.warn("Failed to list sessions from files", e);
            return List.of();
        }
    }

    private Map<String, Object> loadSessionMeta(String sessionId) {
        String metaJson = redisTemplate.opsForValue().get(sessionMetaKey(sessionId));
        if (metaJson == null) return null;
        try {
            Map<String, Object> meta = objectMapper.readValue(metaJson, Map.class);
            List<ChatMessage> messages = getMessages(sessionId);
            meta.put("messageCount", messages.size());
            return meta;
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }
}
