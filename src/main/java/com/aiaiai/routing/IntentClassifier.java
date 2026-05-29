package com.aiaiai.routing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import org.ahocorasick.trie.Emit;
import org.ahocorasick.trie.Trie;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scoring-based query classifier: hard rules for binary intents (CHAT / BOUNDARY / MIXED_INTENT),
 * weighted scoring with negative signals for overlapping categories, LLM fallback when
 * top score is too low or gap too narrow.
 * <p>
 * Trie and extracted tokens are Caffeine-cached per known-titles hash.
 */
@Component
public class IntentClassifier {

    private static final Logger log = LoggerFactory.getLogger(IntentClassifier.class);

    private static final String FALLBACK_PROMPT = """
            你是查询分类器。将用户查询分入以下5类之一，只输出类别名：

            SPECIFIC      - 已命名方法/论文的具体模块、参数、维度、超参数等实现细节
            CONCEPT       - 跨方法的概念性话题、方法论讨论、分类综述（不限于单一方法）
            BROAD         - 极度宽泛的开放问题（最新进展、SOTA、有什么方法）
            VAGUE_REFERENCE - 模糊指代、排除式描述（不是XX的那个、上次说的那个、有个XX的方法）
            ORAL_EMOTIONAL - 口语化、情绪化表达（哎、懒得、太难懂、行不行啊）

            示例：
            "SeedFormer的PC-Attn模块输入维度" → SPECIFIC
            "FoldingNet中的Atlas网格生成流程" → SPECIFIC
            "点云补全中基于Transformer和基于GAN的方法各有什么优缺点" → CONCEPT
            "点云自编码器在补全任务中通常怎么设计损失函数" → CONCEPT
            "2026年点云补全领域的最新进展" → BROAD
            "目前针对稀疏输入做补全的SOTA方法有哪些" → BROAD
            "不是PCN的那个，上次说的另一个做点云上采样的网络" → VAGUE_REFERENCE
            "有个什么方法来着是处理无序点云的" → VAGUE_REFERENCE
            "哎，点云补全真的是太难了看不懂" → ORAL_EMOTIONAL
            "懒得看论文了你直接告诉我结果吧" → ORAL_EMOTIONAL

            只输出类别名，不要任何解释。""";

    private final ChatModel chatModel;
    private final ClassifierConfig config;
    private final KnownPapersRegistry registry;

    private final Cache<String, TrieAndTokens> trieCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(5))
            .maximumSize(5)
            .build();

    private record TrieAndTokens(Trie trie, Set<String> knownTitleSet) {}

    public IntentClassifier(ChatModel chatModel, ClassifierConfig config, KnownPapersRegistry registry) {
        this.chatModel = chatModel;
        this.config = config;
        this.registry = registry;
    }

    // -- public API --

    public ClassificationResult classify(String userMessage) {
        return classifyInternal(userMessage);
    }

    /**
     * Classify with conversation history for reference resolution.
     * Prepends the last user message so entity matching can resolve "它" / "这个" etc.
     */
    public ClassificationResult classify(String userMessage, List<ChatMessage> history) {
        String augmented = userMessage;
        if (history != null && !history.isEmpty()) {
            for (int i = history.size() - 1; i >= 0; i--) {
                if (history.get(i) instanceof UserMessage um) {
                    String text = um.singleText();
                    if (text != null && !text.isBlank()) {
                        augmented = text + " " + userMessage;
                    }
                    break;
                }
            }
        }
        return classifyInternal(augmented);
    }

    // -- internal --

    private ClassificationResult classifyInternal(String q) {
        if (q == null || q.isBlank()) {
            return ClassificationResult.FALLBACK;
        }

        Signals s = extractSignals(q.strip());

        // Hard rules — binary signals with near-zero false-positive rate
        if (s.chatPattern)
            return new ClassificationResult(QueryIntent.CHAT, 1.0,
                    Map.of("chatPattern", 1.0), "RULE");
        if (s.boundaryKeyword)
            return new ClassificationResult(QueryIntent.BOUNDARY, 1.0,
                    Map.of("boundaryKeyword", 1.0), "RULE");
        if (s.memoryIntent)
            return new ClassificationResult(QueryIntent.MIXED_INTENT, 1.0,
                    Map.of("memoryIntent", 1.0), "RULE");

        // -- scoring phase --

        double entityScore = s.knownPaperCount > 0
                ? Math.min(0.5 + (s.knownPaperCount - 1) * 0.1, 0.8)
                : 0;
        int oralCount = s.oralHighCount + s.oralSoftCount;

        Map<String, Double> scores = new LinkedHashMap<>();

        double specific = entityScore
                + (entityScore > 0 && s.detailKeyword ? 0.3 : 0)
                - (s.broadKeyword ? 0.2 : 0)
                - (oralCount >= 2 ? 0.2 : 0);
        scores.put("SPECIFIC", specific);

        double methodName = entityScore
                - (s.detailKeyword || s.comparisonKeyword ? 0.1 : 0)
                - (s.broadKeyword ? 0.3 : 0);
        scores.put("METHOD_NAME", methodName);

        double concept = (s.comparisonKeyword ? 0.3 : 0)
                + (entityScore > 0 ? 0.2 : 0)
                + (s.broadKeyword ? 0.2 : 0);
        scores.put("CONCEPT", concept);

        double broad = (s.broadKeyword ? 0.5 : 0)
                - (entityScore > 0 ? 0.3 : 0)
                - (s.detailKeyword ? 0.2 : 0);
        scores.put("BROAD", broad);

        double vagueRef = (s.vagueRefPattern ? 0.6 : 0)
                + (oralCount > 0 && entityScore == 0 ? 0.2 : 0);
        scores.put("VAGUE_REFERENCE", vagueRef);

        double oralEmotional = Math.min(s.oralHighCount * 0.3 + s.oralSoftCount * 0.1, 0.7);
        scores.put("ORAL_EMOTIONAL", oralEmotional);

        double typoVariant = Math.min(s.abbrCount * 0.2, 0.4)
                - (entityScore > 0 ? 0.3 : 0);
        scores.put("TYPO_VARIANT", typoVariant);

        // Find top 2
        String topIntent = null;
        double topScore = Double.NEGATIVE_INFINITY;
        double secondScore = Double.NEGATIVE_INFINITY;
        for (var entry : scores.entrySet()) {
            double sc = entry.getValue();
            if (sc > topScore) {
                secondScore = topScore;
                topScore = sc;
                topIntent = entry.getKey();
            } else if (sc > secondScore) {
                secondScore = sc;
            }
        }

        log.debug("Scores for '{}': {} (top={}, second={})", q, scores, topScore, secondScore);

        // Decision: threshold + gap checks
        if (topScore < 0.3) {
            log.debug("Top score {} < 0.3, falling back to LLM", topScore);
            return classifyViaLLM(q);
        }
        if (topScore - secondScore < 0.15) {
            log.debug("Gap {} < 0.15, falling back to LLM", topScore - secondScore);
            return classifyViaLLM(q);
        }
        if (topScore / Math.max(secondScore, 0.05) < 1.5) {
            log.debug("Ratio {} < 1.5, falling back to LLM", topScore / Math.max(secondScore, 0.05));
            return classifyViaLLM(q);
        }

        QueryIntent intent = QueryIntent.fromLabel(topIntent);
        Map<String, Double> contributors = buildContributors(s, entityScore);
        return new ClassificationResult(intent, clampScore(topScore), contributors, "RULE");
    }

    private static double clampScore(double score) {
        return Math.min(Math.max(score, 0.0), 1.0);
    }

    private Map<String, Double> buildContributors(Signals s, double entityScore) {
        Map<String, Double> c = new LinkedHashMap<>();
        if (entityScore > 0) c.put("entityScore", entityScore);
        if (s.knownPaperCount > 0) c.put("knownPapers", (double) s.knownPaperCount);
        if (s.abbrCount > 0) c.put("abbrCount", (double) s.abbrCount);
        if (s.detailKeyword) c.put("detailKeyword", 1.0);
        if (s.comparisonKeyword) c.put("comparisonKeyword", 1.0);
        if (s.broadKeyword) c.put("broadKeyword", 1.0);
        if (s.vagueRefPattern) c.put("vagueRefPattern", 1.0);
        if (s.oralHighCount > 0) c.put("oralHighCount", (double) s.oralHighCount);
        if (s.oralSoftCount > 0) c.put("oralSoftCount", (double) s.oralSoftCount);
        return c;
    }

    // -- signal extraction --

    private static class Signals {
        int knownPaperCount;
        int abbrCount;
        boolean memoryIntent;
        int oralHighCount;
        int oralSoftCount;
        boolean broadKeyword;
        boolean detailKeyword;
        boolean comparisonKeyword;
        boolean vagueRefPattern;
        boolean boundaryKeyword;
        boolean chatPattern;
    }

    private Signals extractSignals(String q) {
        Signals s = new Signals();

        s.chatPattern = q.matches("^(你好|嗨|早上好|下午好|晚上好|谢谢|再见|"
                + "hello|hi|hey|thanks|bye|good morning)[\\s!！。.,，?？]*$");

        String lower = q.toLowerCase();
        for (String kw : config.getBoundaryKeywords()) {
            if (lower.contains(kw.toLowerCase())) { s.boundaryKeyword = true; break; }
        }
        for (String trigger : config.getMixedIntentTriggers()) {
            if (q.contains(trigger)) { s.memoryIntent = true; break; }
        }
        for (String marker : config.getOralHighPrecision()) {
            if (q.contains(marker)) s.oralHighCount++;
        }
        for (String marker : config.getOralSoftSignals()) {
            if (q.contains(marker)) s.oralSoftCount++;
        }
        for (String kw : config.getBroadKeywords()) {
            if (lower.contains(kw.toLowerCase())) { s.broadKeyword = true; break; }
        }
        for (String kw : config.getDetailSeekingKeywords()) {
            if (q.contains(kw)) { s.detailKeyword = true; break; }
        }
        for (String kw : config.getComparisonKeywords()) {
            if (q.contains(kw)) { s.comparisonKeyword = true; break; }
        }
        for (String pattern : config.getVagueRefTriggers()) {
            if (Pattern.compile(pattern).matcher(q).find()) { s.vagueRefPattern = true; break; }
        }

        matchEntities(q, s);
        return s;
    }

    private void matchEntities(String q, Signals s) {
        TrieAndTokens tt = getTrieAndTokens();
        if (tt == null) return;

        Collection<Emit> emits = tt.trie.parseText(q);
        Set<String> abbrKeys = config.getTypoCorrections().keySet();

        int paperCount = 0;
        int abbrCount = 0;
        for (Emit emit : emits) {
            String kw = emit.getKeyword();
            if (abbrKeys.contains(kw)) {
                abbrCount++;
            } else if (tt.knownTitleSet.contains(kw)) {
                paperCount++;
            }
            // token fragments are ignored for scoring — they're unreliable evidence
        }

        s.knownPaperCount = paperCount;
        s.abbrCount = abbrCount;
    }

    private TrieAndTokens getTrieAndTokens() {
        Set<String> knownTitles = registry.getKnownTitles();
        if (knownTitles.isEmpty()) return null;

        String key = String.valueOf(knownTitles.hashCode());
        return trieCache.get(key, k -> buildTrie(knownTitles));
    }

    private TrieAndTokens buildTrie(Set<String> knownTitles) {
        Set<String> abbrKeys = config.getTypoCorrections().keySet();
        Set<String> tokens = extractTokens(knownTitles);

        Trie.TrieBuilder builder = Trie.builder();
        for (String title : knownTitles) builder.addKeyword(title);
        for (String token : tokens) builder.addKeyword(token);
        for (String abbr : abbrKeys) builder.addKeyword(abbr);

        log.debug("Built Aho-Corasick trie: {} titles + {} tokens + {} abbrs",
                knownTitles.size(), tokens.size(), abbrKeys.size());
        return new TrieAndTokens(builder.build(), knownTitles);
    }

    /**
     * Extract likely method-name tokens from compound titles.
     * E.g. "ICCV_2021 PoinTr" → ["ICCV", "PoinTr"]
     * Filters out title-cased common words and keeps only all-UPPERCASE
     * acronyms (≤5 chars) or mixed-case proper nouns.
     */
    static Set<String> extractTokens(Set<String> titles) {
        Set<String> tokens = new HashSet<>();
        for (String title : titles) {
            for (String part : title.split("[ _\\-]+")) {
                if (part.isEmpty() || part.length() < 2) continue;
                if (part.matches("\\d+")) continue;

                boolean hasUpper = part.chars().anyMatch(Character::isUpperCase);
                boolean hasLower = part.chars().anyMatch(Character::isLowerCase);
                if (!hasUpper) continue;

                // All-uppercase acronym (e.g. PCN, PMP, DiPT): keep if ≤5 chars
                if (!hasLower) {
                    if (part.length() <= 5) tokens.add(part);
                    continue;
                }

                // Mixed case: only keep if there's an uppercase letter beyond position 0
                for (int i = 1; i < part.length(); i++) {
                    if (Character.isUpperCase(part.charAt(i))) {
                        tokens.add(part);
                        break;
                    }
                }
            }
        }
        return tokens;
    }

    // -- LLM fallback --

    private ClassificationResult classifyViaLLM(String q) {
        try {
            var resp = chatModel.chat(
                    SystemMessage.from(FALLBACK_PROMPT),
                    UserMessage.from(q));
            String label = resp.aiMessage().text();
            log.debug("LLM fallback classified: '{}' → {}", q, label);
            QueryIntent intent = QueryIntent.fromLabel(label);
            return new ClassificationResult(intent, 0.7,
                    Map.of("llmLabel", 1.0), "LLM_FALLBACK");
        } catch (Exception e) {
            log.warn("LLM fallback failed, using FALLBACK: {}", e.getMessage());
            return ClassificationResult.FALLBACK;
        }
    }
}
