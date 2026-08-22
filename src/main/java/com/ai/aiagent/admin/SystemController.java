package com.ai.aiagent.admin;

import com.ai.aiagent.chat.AnswerCacheService;
import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.ingest.DocumentFormat;
import com.ai.aiagent.llm.EmbeddingService;
import com.ai.aiagent.llm.InternalLlm;
import com.ai.aiagent.llm.LlmClientFactory;
import com.ai.aiagent.llm.LlmProvider;
import com.ai.aiagent.observability.RagMetrics;
import com.ai.aiagent.rerank.RerankerProvider;
import com.ai.aiagent.security.PathAllowlist;
import com.ai.aiagent.settings.RagSettingsService;
import com.ai.aiagent.store.ChunkRepository;
import com.ai.aiagent.store.ConversationRepository;
import com.ai.aiagent.store.DocumentRepository;
import com.ai.aiagent.store.FeedbackRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag/admin")
@Slf4j
public class SystemController {

    private final RagMetrics metrics;
    private final AnswerCacheService cache;
    private final FeedbackRepository feedback;
    private final DocumentRepository documents;
    private final ChunkRepository chunks;
    private final ConversationRepository conversations;
    private final EmbeddingService embeddings;
    private final InternalLlm internalLlm;
    private final LlmClientFactory clients;
    private final RerankerProvider rerankers;
    private final RagSettingsService settings;
    private final PathAllowlist allowlist;
    private final RagProperties props;

    public SystemController(RagMetrics metrics, AnswerCacheService cache,
                            FeedbackRepository feedback, DocumentRepository documents,
                            ChunkRepository chunks, ConversationRepository conversations,
                            EmbeddingService embeddings, InternalLlm internalLlm,
                            LlmClientFactory clients, RerankerProvider rerankers,
                            RagSettingsService settings, PathAllowlist allowlist,
                            RagProperties props) {
        this.metrics = metrics;
        this.cache = cache;
        this.feedback = feedback;
        this.documents = documents;
        this.chunks = chunks;
        this.conversations = conversations;
        this.embeddings = embeddings;
        this.internalLlm = internalLlm;
        this.clients = clients;
        this.rerankers = rerankers;
        this.settings = settings;
        this.allowlist = allowlist;
        this.props = props;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> corpus = new LinkedHashMap<>(documents.stats());
        corpus.put("chunksInIndex", chunks.count());
        out.put("corpus", corpus);

        out.put("metrics", metrics.snapshot());
        out.put("cache", cache.stats());
        out.put("feedback", feedback.stats());

        Map<String, Object> pipeline = new LinkedHashMap<>();
        pipeline.put("answerModel", settings.current().provider() + "/" + settings.current().model());
        pipeline.put("internalModel", internalLlm.describe());
        pipeline.put("embeddingModel", embeddings.modelName());
        pipeline.put("embeddingDimensions", embeddings.dimensions());
        pipeline.put("embeddingReady", embeddings.isReady());
        pipeline.put("reranker", rerankers.get().name());
        pipeline.put("hybridEnabled", props.getRetrieval().isHybridEnabled());
        pipeline.put("multiQueryEnabled", props.getRetrieval().isMultiQueryEnabled());
        pipeline.put("hydeEnabled", props.getRetrieval().isHydeEnabled());
        pipeline.put("contextualEnabled", props.getIngestion().isContextualEnabled());
        pipeline.put("abstainEnabled", props.getRetrieval().isAbstainWhenBelowThreshold());
        pipeline.put("chunkingStrategy", props.getChunking().getStrategy());
        out.put("pipeline", pipeline);

        Map<String, Object> providers = new LinkedHashMap<>();
        for (LlmProvider p : LlmProvider.values()) {
            providers.put(p.name(), clients.isAvailable(p));
        }
        out.put("providers", providers);

        Map<String, Object> ingest = new LinkedHashMap<>();
        ingest.put("allowedRoots", allowlist.configuredRoots());
        ingest.put("supportedExtensions", DocumentFormat.allExtensions());
        ingest.put("skipUnchanged", props.getIngestion().isSkipUnchanged());
        ingest.put("ocrEnabled", props.getOcr().isEnabled());
        ingest.put("ocrModel", props.getOcr().getProvider() + "/" + props.getOcr().getModel());
        ingest.put("antivirusEnabled", props.getAntivirus().isEnabled());
        out.put("ingest", ingest);

        Map<String, Object> compliance = new LinkedHashMap<>();
        compliance.put("auditEnabled", props.getAudit().isEnabled());
        compliance.put("auditIncludeRead", props.getAudit().isIncludeRead());
        compliance.put("retentionEnabled", props.getRetention().isEnabled());
        compliance.put("conversationDays", props.getRetention().getConversationDays());
        compliance.put("auditDays", props.getRetention().getAuditDays());
        out.put("compliance", compliance);

        return out;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    @GetMapping("/feedback/negative")
    public Map<String, Object> negativeFeedback(@RequestParam(defaultValue = "30") int limit) {
        return Map.of("items", feedback.recentNegative(limit));
    }

    @GetMapping("/cache")
    public Map<String, Object> cacheStats() {
        return cache.stats();
    }

    @DeleteMapping("/cache")
    public Map<String, Object> clearCache() {
        int cleared = cache.clear();
        return Map.of("cleared", cleared, "message", "Đã xoá " + cleared + " bản ghi cache.");
    }

    @DeleteMapping("/conversations/purge")
    public Map<String, Object> purgeConversations(@RequestParam(defaultValue = "90") int olderThanDays) {
        int deleted = conversations.purgeInactiveOlderThanDays(olderThanDays);
        return Map.of("deleted", deleted,
                "message", "Đã xoá " + deleted + " hội thoại không hoạt động quá "
                        + olderThanDays + " ngày.");
    }
}
