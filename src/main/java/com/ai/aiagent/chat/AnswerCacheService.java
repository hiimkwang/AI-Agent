package com.ai.aiagent.chat;

import com.ai.aiagent.common.Hashes;
import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.store.AnswerCacheRepository;
import com.ai.aiagent.store.StoreModels;
import com.ai.aiagent.store.StoreModels.Citation;
import com.ai.aiagent.store.TsQueryBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

@Service
@Slf4j
public class AnswerCacheService {

    public record CachedAnswer(String answer, List<Citation> citations, String provider,
                               String model, String kind, double similarity, long cacheId) {
    }

    private volatile Pattern refusal;
    private volatile String refusalSource;

    private final AnswerCacheRepository repository;
    private final RagProperties props;
    private final ObjectMapper mapper;

    public AnswerCacheService(AnswerCacheRepository repository,
                              RagProperties props, ObjectMapper mapper) {
        this.repository = repository;
        this.props = props;
        this.mapper = mapper;
    }

    public boolean isEnabled() {
        return props.getCache().isEnabled();
    }

    public Optional<CachedAnswer> lookup(String question, AccessScope scope, String category,
                                         String provider, String model, float[] queryEmbedding) {
        return lookup(question, scope, category, provider, model, queryEmbedding, null);
    }

    public Optional<CachedAnswer> lookup(String question, AccessScope scope, String category,
                                         String provider, String model, float[] queryEmbedding,
                                         String conversationContext) {
        if (!isEnabled()) return Optional.empty();

        String scopeKey = scopeKey(scope, category, provider, model, conversationContext);
        String exactKey = cacheKey(question, scopeKey);

        Optional<AnswerCacheRepository.Hit> exact = repository.findExact(exactKey);
        if (exact.isPresent()) {
            repository.recordHit(exact.get().id());
            log.debug("Answer cache hit (exact): {}", abbreviate(question));
            return Optional.of(toCached(exact.get()));
        }

        if (props.getCache().isSemanticEnabled() && queryEmbedding != null) {
            Optional<AnswerCacheRepository.Hit> semantic = repository.findSemantic(
                    scopeKey, queryEmbedding, props.getCache().getSemanticThreshold());
            if (semantic.isPresent()) {
                repository.recordHit(semantic.get().id());
                log.debug("Answer cache hit (semantic, similarity={}): {}",
                        String.format("%.3f", semantic.get().similarity()), abbreviate(question));
                return Optional.of(toCached(semantic.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * True when the model answered that it could not find anything. Such an answer is never
     * cached: see {@link RagProperties.Cache#getRefusalPattern()}.
     */
    public boolean looksLikeRefusal(String answer) {
        if (answer == null || answer.isBlank()) return true;
        String pattern = props.getCache().getRefusalPattern();
        if (pattern == null || pattern.isBlank()) return false;
        try {
            Pattern compiled = refusal;
            if (compiled == null || !pattern.equals(refusalSource)) {
                compiled = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
                refusal = compiled;
                refusalSource = pattern;
            }
            String plain = TsQueryBuilder.stripDiacritics(answer).toLowerCase();
            return compiled.matcher(plain).find();
        } catch (PatternSyntaxException e) {
            log.warn("cache.refusalPattern is not a valid regex ({}); caching every answer until "
                    + "it is fixed.", e.getDescription());
            return false;
        }
    }

    public void store(String question, AccessScope scope, String category, String provider,
                      String model, String answer, List<Citation> citations, float[] queryEmbedding) {
        store(question, scope, category, provider, model, answer, citations, queryEmbedding, null);
    }

    public void store(String question, AccessScope scope, String category, String provider,
                      String model, String answer, List<Citation> citations,
                      float[] queryEmbedding, String conversationContext) {
        if (!isEnabled() || answer == null || answer.isBlank()) return;
        if (looksLikeRefusal(answer)) {
            log.debug("Not caching a not-found answer for: {}", abbreviate(question));
            return;
        }
        String scopeKey = scopeKey(scope, category, provider, model, conversationContext);
        repository.put(cacheKey(question, scopeKey), scopeKey, question, answer, citations,
                provider, model, queryEmbedding, props.getCache().getTtlMinutes());
    }

    public int clear() {
        return repository.clear();
    }

    /**
     * Drop every cached answer for this exact question, across all scopes. Called when a user
     * marks an answer as bad: without it the same answer keeps being served for the rest of the
     * TTL and the feedback button looks broken. Deleting other scopes' copies too is deliberate -
     * a bad answer is bad for everyone, and the cache is a speed-up, not an access boundary.
     */
    public int invalidate(String question) {
        if (question == null || question.isBlank()) return 0;
        return repository.deleteByQuestion(question.strip());
    }

    public java.util.Map<String, Object> stats() {
        return repository.stats();
    }

    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 60 * 1000)
    public void housekeeping() {
        if (!isEnabled()) return;
        try {
            int expired = repository.purgeExpired();
            int trimmed = repository.trimTo(props.getCache().getMaxEntries());
            if (expired + trimmed > 0) {
                log.debug("Answer cache housekeeping: {} expired removed, {} old entries trimmed.",
                    expired, trimmed);
            }
        } catch (Exception e) {
            log.warn("Answer cache housekeeping failed: {}", e.getMessage());
        }
    }

    static String normalizeQuestion(String question) {
        if (question == null) return "";
        String q = TsQueryBuilder.stripDiacritics(question).toLowerCase();
        q = q.replaceAll("[\\p{Punct}]+$", "");
        return q.replaceAll("\\s+", " ").strip();
    }

    private String cacheKey(String question, String scopeKey) {
        return Hashes.sha256(normalizeQuestion(question) + "|" + scopeKey);
    }

    /**
     * @param conversationContext fingerprint of the turns this answer depends on, or null for a
     *                            question that stands on its own.
     *
     *                            <p>Without it a follow-up leaks across conversations: "Lenh co so
     *                            ay" is cached by those four words alone, so the next person to
     *                            type them - in a conversation about something else entirely -
     *                            gets this conversation's answer. The semantic cache at cosine
     *                            0.97 makes it worse, because short follow-ups are all alike.
     *                            First questions carry no context and keep sharing one entry,
     *                            which is where the cache earns its keep anyway.
     */
    private String scopeKey(AccessScope scope, String category, String provider, String model,
                            String conversationContext) {
        return scope.cacheScopeKey()
                + "|" + (category == null || category.isBlank() ? "*" : category.toLowerCase())
                + "|" + provider + "/" + model
                + (conversationContext == null || conversationContext.isBlank()
                        ? "" : "|ctx:" + Hashes.sha256(conversationContext));
    }

    /** Null when there is nothing earlier, so a first question keeps the shared cache entry. */
    public static String contextFingerprint(List<StoreModels.Turn> history) {
        if (history == null || history.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (StoreModels.Turn t : history) {
            sb.append(t.role()).append(':').append(t.content() == null ? "" : t.content())
                    .append('\u0000');
        }
        return sb.toString();
    }

    private CachedAnswer toCached(AnswerCacheRepository.Hit hit) {
        List<Citation> citations = List.of();
        if (hit.citationsJson() != null && !hit.citationsJson().isBlank()) {
            try {
                citations = mapper.readValue(hit.citationsJson(), new TypeReference<List<Citation>>() {
                });
            } catch (Exception e) {
                log.debug("Could not read cached citations, serving answer without them: {}", e.getMessage());
            }
        }
        return new CachedAnswer(hit.answer(), citations, hit.provider(), hit.model(),
                hit.kind(), hit.similarity(), hit.id());
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 60 ? s : s.substring(0, 60) + "...";
    }
}
