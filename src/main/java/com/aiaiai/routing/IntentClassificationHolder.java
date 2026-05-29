package com.aiaiai.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Caffeine-backed session-scoped classification result storage.
 * TTL 30s prevents memory leak if cleanup callbacks never fire.
 */
@Component
public class IntentClassificationHolder {

    private final Cache<String, ClassificationResult> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();

    public void put(String sessionId, ClassificationResult result) {
        cache.put(sessionId, result);
    }

    public ClassificationResult get(String sessionId) {
        ClassificationResult result = cache.getIfPresent(sessionId);
        return result != null ? result : ClassificationResult.FALLBACK;
    }

    public void invalidate(String sessionId) {
        cache.invalidate(sessionId);
    }
}
