package com.aiaiai.memory;

public interface MemoryService {
    void save(String content);
    String recall(String query);
    MemoryRecallResult recallStructured(String query);
}
