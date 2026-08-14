package com.ai.aiagent.settings;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.llm.LlmProvider;
import com.ai.aiagent.store.SettingsRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cau hinh doi duoc LUC RUNTIME va luu ben.
 *
 * Truoc day cac tham so retrieval bind bang {@code @Value} vao field cua tung service
 * -> chi doc mot lan luc tao bean, muon thu {@code top-k} khac phai restart, nen tren
 * thuc te khong ai thu va viec tinh chinh bang eval tro thanh vo nghia.
 *
 * Gio: sua qua API -> ap ngay vao {@link RagProperties} (bean dung chung) -> ghi xuong
 * bang {@code rag_settings} -> nap lai khi khoi dong. Doi {@code min-rerank-score} hay
 * {@code candidates} roi chay lai eval trong cung mot phien lam viec.
 */
@Service
@Slf4j
public class RagSettingsService {

    public record ModelSelection(LlmProvider provider, String model) {
    }

    /** Cac khoa duoc phep sua luc runtime, kem cach doc/ghi vao RagProperties. */
    private static final String K_PROVIDER = "llm.provider";
    private static final String K_MODEL = "llm.model";
    private static final String K_INTERNAL_PROVIDER = "internal.provider";
    private static final String K_INTERNAL_MODEL = "internal.model";
    private static final String K_TOP_K = "retrieval.topK";
    private static final String K_CANDIDATES = "retrieval.candidates";
    private static final String K_VECTOR_TOP_K = "retrieval.vectorTopK";
    private static final String K_FULLTEXT_TOP_K = "retrieval.fulltextTopK";
    private static final String K_HYBRID = "retrieval.hybridEnabled";
    private static final String K_MULTI_QUERY = "retrieval.multiQueryEnabled";
    private static final String K_HYDE = "retrieval.hydeEnabled";
    private static final String K_MIN_RERANK = "retrieval.minRerankScore";
    private static final String K_MIN_VECTOR = "retrieval.minVectorScore";
    private static final String K_ABSTAIN = "retrieval.abstainWhenBelowThreshold";
    private static final String K_RECENCY = "retrieval.recencyBoostEnabled";
    private static final String K_EXCLUDE_EXPIRED = "retrieval.excludeExpired";
    private static final String K_RERANK_PROVIDER = "rerank.provider";
    private static final String K_CACHE_ENABLED = "cache.enabled";
    private static final String K_CACHE_SEMANTIC = "cache.semanticEnabled";
    private static final String K_CACHE_THRESHOLD = "cache.semanticThreshold";
    private static final String K_CONTEXTUAL = "ingestion.contextualEnabled";
    private static final String K_REWRITE = "queryRewrite.enabled";
    private static final String K_CLARIFY = "retrieval.clarifyAmbiguousEnabled";
    private static final String K_CITATIONS = "chat.citationsEnabled";

    private final RagProperties props;
    private final SettingsRepository repository;
    private final AtomicReference<ModelSelection> current = new AtomicReference<>();

    public RagSettingsService(RagProperties props, SettingsRepository repository) {
        this.props = props;
        this.repository = repository;
    }

    @PostConstruct
    void init() {
        Map<String, String> stored = repository.loadAll();
        int applied = 0;
        for (Map.Entry<String, String> e : stored.entrySet()) {
            // Khoa "provider.*" thuoc ve ProviderSettingsService (API key/model provider),
            // khong phai cua service nay - bo qua de khong log canh bao gia moi lan khoi dong.
            if (e.getKey().startsWith(ProviderSettingsService.KEY_PREFIX)) continue;
            try {
                if (applyOne(e.getKey(), e.getValue())) applied++;
            } catch (Exception ex) {
                log.warn("Bo qua cau hinh luu san '{}={}': {}", e.getKey(), e.getValue(), ex.getMessage());
            }
        }
        LlmProvider provider = LlmProvider.fromString(props.getLlm().getDefaultProvider());
        current.set(new ModelSelection(provider == null ? LlmProvider.OPENAI : provider,
                props.getLlm().getDefaultModel()));
        log.info("Cau hinh LLM mac dinh: {}/{} (noi bo: {}/{}), da ap {} cau hinh luu san.",
                current.get().provider(), current.get().model(),
                props.getInternal().getProvider(), props.getInternal().getModel(), applied);
    }

    public ModelSelection current() {
        return current.get();
    }

    /** Toan bo gia tri hien tai cua cac khoa sua duoc, cho trang quan tri. */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(K_PROVIDER, props.getLlm().getDefaultProvider());
        m.put(K_MODEL, props.getLlm().getDefaultModel());
        m.put(K_INTERNAL_PROVIDER, props.getInternal().getProvider());
        m.put(K_INTERNAL_MODEL, props.getInternal().getModel());
        m.put(K_TOP_K, props.getRetrieval().getTopK());
        m.put(K_CANDIDATES, props.getRetrieval().getCandidates());
        m.put(K_VECTOR_TOP_K, props.getRetrieval().getVectorTopK());
        m.put(K_FULLTEXT_TOP_K, props.getRetrieval().getFulltextTopK());
        m.put(K_HYBRID, props.getRetrieval().isHybridEnabled());
        m.put(K_MULTI_QUERY, props.getRetrieval().isMultiQueryEnabled());
        m.put(K_HYDE, props.getRetrieval().isHydeEnabled());
        m.put(K_MIN_RERANK, props.getRetrieval().getMinRerankScore());
        m.put(K_MIN_VECTOR, props.getRetrieval().getMinVectorScore());
        m.put(K_ABSTAIN, props.getRetrieval().isAbstainWhenBelowThreshold());
        m.put(K_RECENCY, props.getRetrieval().isRecencyBoostEnabled());
        m.put(K_EXCLUDE_EXPIRED, props.getRetrieval().isExcludeExpired());
        m.put(K_RERANK_PROVIDER, props.getRerank().getProvider());
        m.put(K_CACHE_ENABLED, props.getCache().isEnabled());
        m.put(K_CACHE_SEMANTIC, props.getCache().isSemanticEnabled());
        m.put(K_CACHE_THRESHOLD, props.getCache().getSemanticThreshold());
        m.put(K_CONTEXTUAL, props.getIngestion().isContextualEnabled());
        m.put(K_REWRITE, props.getQueryRewrite().isEnabled());
        m.put(K_CLARIFY, props.getRetrieval().isClarifyAmbiguousEnabled());
        m.put(K_CITATIONS, props.getChat().isCitationsEnabled());
        return m;
    }

    public List<String> editableKeys() {
        return List.copyOf(snapshot().keySet());
    }

    /**
     * Ap va luu nhieu cau hinh mot luc.
     *
     * @return danh sach khoa da doi
     */
    public List<String> update(Map<String, Object> changes, String updatedBy) {
        List<String> changed = new java.util.ArrayList<>();
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            String key = e.getKey();
            String value = e.getValue() == null ? null : String.valueOf(e.getValue());
            if (!applyOne(key, value)) {
                throw new IllegalArgumentException("Khoa cau hinh khong ho tro: '" + key
                        + "'. Cac khoa hop le: " + editableKeys());
            }
            repository.put(key, value, updatedBy);
            changed.add(key);
        }
        // Provider/model mac dinh duoc cache rieng nen phai lam moi
        LlmProvider provider = LlmProvider.fromString(props.getLlm().getDefaultProvider());
        current.set(new ModelSelection(provider == null ? LlmProvider.OPENAI : provider,
                props.getLlm().getDefaultModel()));
        log.info("Da cap nhat {} cau hinh boi {}: {}", changed.size(), updatedBy, changed);
        return changed;
    }

    /** Doi provider/model tra loi mac dinh (loi tat quen dung nhat). */
    public ModelSelection updateModel(LlmProvider provider, String model, String updatedBy) {
        Map<String, Object> changes = new LinkedHashMap<>();
        if (provider != null) changes.put(K_PROVIDER, provider.name());
        if (model != null && !model.isBlank()) changes.put(K_MODEL, model.trim());
        if (!changes.isEmpty()) update(changes, updatedBy);
        return current();
    }

    public void resetToFileDefaults() {
        repository.clear();
        log.info("Da xoa cau hinh luu trong DB - se tra ve gia tri trong application.properties "
                + "sau khi khoi dong lai.");
    }

    /** @return false neu khoa khong duoc ho tro. */
    private boolean applyOne(String key, String value) {
        RagProperties.Retrieval r = props.getRetrieval();
        RagProperties.Cache c = props.getCache();
        switch (key) {
            case K_PROVIDER -> {
                LlmProvider p = LlmProvider.fromString(value);
                if (p == null) throw new IllegalArgumentException("Provider trong.");
                props.getLlm().setDefaultProvider(p.name());
            }
            case K_MODEL -> props.getLlm().setDefaultModel(requireText(value));
            case K_INTERNAL_PROVIDER -> {
                LlmProvider p = LlmProvider.fromString(value);
                if (p == null) throw new IllegalArgumentException("Provider trong.");
                props.getInternal().setProvider(p.name());
            }
            case K_INTERNAL_MODEL -> props.getInternal().setModel(requireText(value));
            case K_TOP_K -> r.setTopK(intInRange(value, 1, 30));
            case K_CANDIDATES -> r.setCandidates(intInRange(value, 1, 200));
            case K_VECTOR_TOP_K -> r.setVectorTopK(intInRange(value, 1, 200));
            case K_FULLTEXT_TOP_K -> r.setFulltextTopK(intInRange(value, 0, 200));
            case K_HYBRID -> r.setHybridEnabled(bool(value));
            case K_MULTI_QUERY -> r.setMultiQueryEnabled(bool(value));
            case K_HYDE -> r.setHydeEnabled(bool(value));
            case K_MIN_RERANK -> r.setMinRerankScore(doubleInRange(value, 0, 1));
            case K_MIN_VECTOR -> r.setMinVectorScore(doubleInRange(value, 0, 1));
            case K_ABSTAIN -> r.setAbstainWhenBelowThreshold(bool(value));
            case K_RECENCY -> r.setRecencyBoostEnabled(bool(value));
            case K_EXCLUDE_EXPIRED -> r.setExcludeExpired(bool(value));
            case K_RERANK_PROVIDER -> {
                String v = requireText(value).toUpperCase();
                if (!List.of("LLM", "COHERE", "NONE").contains(v)) {
                    throw new IllegalArgumentException("rerank.provider phai la LLM, COHERE hoac NONE.");
                }
                props.getRerank().setProvider(v);
            }
            case K_CACHE_ENABLED -> c.setEnabled(bool(value));
            case K_CACHE_SEMANTIC -> c.setSemanticEnabled(bool(value));
            case K_CACHE_THRESHOLD -> c.setSemanticThreshold(doubleInRange(value, 0.5, 1.0));
            case K_CONTEXTUAL -> props.getIngestion().setContextualEnabled(bool(value));
            case K_REWRITE -> props.getQueryRewrite().setEnabled(bool(value));
            case K_CLARIFY -> r.setClarifyAmbiguousEnabled(bool(value));
            case K_CITATIONS -> props.getChat().setCitationsEnabled(bool(value));
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Gia tri khong duoc de trong.");
        }
        return value.strip();
    }

    private static boolean bool(String value) {
        return Boolean.parseBoolean(requireText(value));
    }

    private static int intInRange(String value, int min, int max) {
        int v;
        try {
            v = Integer.parseInt(requireText(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Gia tri phai la so nguyen: " + value);
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException("Gia tri phai trong khoang [" + min + ", " + max + "].");
        }
        return v;
    }

    private static double doubleInRange(String value, double min, double max) {
        double v;
        try {
            v = Double.parseDouble(requireText(value));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Gia tri phai la so: " + value);
        }
        if (v < min || v > max) {
            throw new IllegalArgumentException("Gia tri phai trong khoang [" + min + ", " + max + "].");
        }
        return v;
    }
}
