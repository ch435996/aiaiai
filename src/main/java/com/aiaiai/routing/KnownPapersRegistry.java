package com.aiaiai.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Dynamic registry of known paper titles / method names.
 * Populated on ingestion, backed by Redis, cached in Caffeine (5 min TTL).
 * On startup, if Redis is empty, probes Pinecone to backfill.
 */
@Component
public class KnownPapersRegistry {

    private static final Logger log = LoggerFactory.getLogger(KnownPapersRegistry.class);

    private static final String REDIS_KEY = "papers:known-titles";

    private static final String[] PROBE_QUERIES = {
            "point cloud completion",
            "3D reconstruction shape completion",
            "point cloud upsampling",
            "diffusion model point cloud",
            "transformer point cloud",
            "coarse-to-fine point cloud",
            "point cloud evaluation metrics",
    };

    private final RedisTemplate<String, String> redisTemplate;
    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> knowledgeStore;

    private final Cache<String, Set<String>> cache = Caffeine.newBuilder()
            .expireAfterWrite(5, TimeUnit.MINUTES)
            .build();

    public KnownPapersRegistry(
            RedisTemplate<String, String> redisTemplate,
            @Qualifier("embeddingModelV2") EmbeddingModel embeddingModel,
            @Qualifier("knowledgeStoreV2") EmbeddingStore<TextSegment> knowledgeStore) {
        this.redisTemplate = redisTemplate;
        this.embeddingModel = embeddingModel;
        this.knowledgeStore = knowledgeStore;
    }

    public Set<String> getKnownTitles() {
        Set<String> titles = cache.getIfPresent("titles");
        if (titles != null) return titles;

        titles = loadFromRedis();
        if (titles.isEmpty()) {
            titles = probePinecone();
            if (!titles.isEmpty()) {
                redisTemplate.opsForSet().add(REDIS_KEY, titles.toArray(new String[0]));
            }
        }
        cache.put("titles", titles);
        return titles;
    }

    public void register(String rawTitle) {
        String normalized = normalizeTitle(rawTitle);
        Set<String> names = expandNames(normalized);
        redisTemplate.opsForSet().add(REDIS_KEY, names.toArray(new String[0]));
        cache.invalidate("titles");
        log.debug("Registered paper title: {} → {}", normalized, names);
    }

    /** Preload titles directly into cache, bypassing Redis/Pinecone. For testing. */
    public void preload(Set<String> titles) {
        cache.put("titles", new java.util.HashSet<>(titles));
    }

    private Set<String> loadFromRedis() {
        Set<String> titles = redisTemplate.opsForSet().members(REDIS_KEY);
        return titles != null ? titles : Set.of();
    }

    private Set<String> probePinecone() {
        log.info("Probing Pinecone for known paper titles...");
        Set<String> allTitles = new LinkedHashSet<>();
        for (String query : PROBE_QUERIES) {
            try {
                Embedding emb = embeddingModel.embed(query).content();
                var result = knowledgeStore.search(
                        EmbeddingSearchRequest.builder()
                                .queryEmbedding(emb).maxResults(20).minScore(0.0).build());
                for (EmbeddingMatch<TextSegment> m : result.matches()) {
                    var meta = m.embedded().metadata();
                    String title = meta != null ? meta.getString("title") : null;
                    if (title != null && !title.isBlank()) {
                        String n = normalizeTitle(title);
                        allTitles.add(n);
                        allTitles.addAll(expandNames(n));
                    }
                }
            } catch (Exception e) {
                log.warn("Probe query '{}' failed: {}", query, e.getMessage());
            }
        }
        log.info("Probed {} unique paper titles from Pinecone", allTitles.size());
        return allTitles;
    }

    static String normalizeTitle(String raw) {
        return raw.replace(".pdf", "").replace(".PDF", "").strip();
    }

    /**
     * Venue prefix pattern: "ICCV_2021 ", "CVPR2022_", "ECCV_2020 " etc.
     * Strips leading VENUE_YEAR prefix to extract the method name.
     */
    private static final Pattern VENUE_PREFIX = Pattern.compile(
            "^[A-Za-z]+_?\\d{2,4}[ _\\-]+");

    static Set<String> expandNames(String title) {
        Set<String> names = new LinkedHashSet<>();
        names.add(title);
        String shortName = VENUE_PREFIX.matcher(title).replaceFirst("");
        if (!shortName.isEmpty() && !shortName.equals(title)) {
            names.add(shortName);
        }
        return names;
    }
}
