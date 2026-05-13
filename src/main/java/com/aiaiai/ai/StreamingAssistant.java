package com.aiaiai.ai;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.UserMessage;

public interface StreamingAssistant {
    TokenStream chat(@MemoryId String sessionId, @UserMessage String message);
}
