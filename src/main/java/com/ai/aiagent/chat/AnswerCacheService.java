package com.ai.aiagent.chat;

import com.ai.aiagent.common.Hashes;
import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.store.AnswerCacheRepository;
import com.ai.aiagent.store.StoreModels.Citation;
import com.ai.aiagent.store.TsQueryBuilder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Cache cau tra loi, hai tang.
 *
 * Truoc day khong co cache nao, nen mot cau hoi duoc muoi nguoi hoi la muoi lan tra
 * du 3-4 loi goi LLM. Cau hoi noi bo lap lai rat nhieu ("nghi phep bao nhieu ngay",
 * "phu cap an trua"), nen day la cho tiet kiem lon nhat ve chi phi va do tre.
 *
 * Cache key LUON gom pham vi truy cap va provider/model:
 *   - pham vi: khong bao gio tra cau tra loi cua phong ban khac cho nguoi khong co quyen
 *   - provider/model: doi model la doi chat luong, khong duoc tra lai ket qua cu
 */
@Service
@Slf4j
public class AnswerCacheService {

    public record CachedAnswer(String answer, List<Citation> citations, String provider,
                               String model, String kind, double similarity, long cacheId) {
    }

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

    /**
     * @param queryEmbedding vector cau hoi neu da tinh san (tai su dung, khong nhung lai)
     */
    public Optional<CachedAnswer> lookup(String question, AccessScope scope, String category,
                                         String provider, String model, float[] queryEmbedding) {
        if (!isEnabled()) return Optional.empty();

        String scopeKey = scopeKey(scope, category, provider, model);
        String exactKey = cacheKey(question, scopeKey);

        Optional<AnswerCacheRepository.Hit> exact = repository.findExact(exactKey);
        if (exact.isPresent()) {
            repository.recordHit(exact.get().id());
            log.debug("Cache HIT (exact) cho: {}", abbreviate(question));
            return Optional.of(toCached(exact.get()));
        }

        if (props.getCache().isSemanticEnabled() && queryEmbedding != null) {
            Optional<AnswerCacheRepository.Hit> semantic = repository.findSemantic(
                    scopeKey, queryEmbedding, props.getCache().getSemanticThreshold());
            if (semantic.isPresent()) {
                repository.recordHit(semantic.get().id());
                log.debug("Cache HIT (semantic, sim={}) cho: {}",
                        String.format("%.3f", semantic.get().similarity()), abbreviate(question));
                return Optional.of(toCached(semantic.get()));
            }
        }
        return Optional.empty();
    }

    public void store(String question, AccessScope scope, String category, String provider,
                      String model, String answer, List<Citation> citations, float[] queryEmbedding) {
        if (!isEnabled() || answer == null || answer.isBlank()) return;
        String scopeKey = scopeKey(scope, category, provider, model);
        repository.put(cacheKey(question, scopeKey), scopeKey, question, answer, citations,
                provider, model, queryEmbedding, props.getCache().getTtlMinutes());
    }

    public int clear() {
        return repository.clear();
    }

    public java.util.Map<String, Object> stats() {
        return repository.stats();
    }

    /** Don cache het han va cat bot ban ghi cu, moi 15 phut. */
    @Scheduled(fixedDelay = 15 * 60 * 1000, initialDelay = 60 * 1000)
    public void housekeeping() {
        if (!isEnabled()) return;
        try {
            int expired = repository.purgeExpired();
            int trimmed = repository.trimTo(props.getCache().getMaxEntries());
            if (expired + trimmed > 0) {
                log.debug("Cache housekeeping: xoa {} het han, cat {} ban ghi cu.", expired, trimmed);
            }
        } catch (Exception e) {
            log.warn("Cache housekeeping loi: {}", e.getMessage());
        }
    }

    /**
     * Chuan hoa cau hoi truoc khi hash: bo dau, chu thuong, gop khoang trang, bo dau
     * cau cuoi. Nho vay "Nghi phep bao nhieu ngay?" va "nghỉ phép bao nhiêu ngày"
     * dung cung mot ban ghi cache.
     */
    static String normalizeQuestion(String question) {
        if (question == null) return "";
        String q = TsQueryBuilder.stripDiacritics(question).toLowerCase();
        q = q.replaceAll("[\\p{Punct}]+$", "");
        return q.replaceAll("\\s+", " ").strip();
    }

    private String cacheKey(String question, String scopeKey) {
        return Hashes.sha256(normalizeQuestion(question) + "|" + scopeKey);
    }

    private String scopeKey(AccessScope scope, String category, String provider, String model) {
        return scope.cacheScopeKey()
                + "|" + (category == null || category.isBlank() ? "*" : category.toLowerCase())
                + "|" + provider + "/" + model;
    }

    private CachedAnswer toCached(AnswerCacheRepository.Hit hit) {
        List<Citation> citations = List.of();
        if (hit.citationsJson() != null && !hit.citationsJson().isBlank()) {
            try {
                citations = mapper.readValue(hit.citationsJson(), new TypeReference<List<Citation>>() {
                });
            } catch (Exception e) {
                log.debug("Khong doc duoc trich dan trong cache: {}", e.getMessage());
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
