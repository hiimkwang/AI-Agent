package com.ai.aiagent.chat;

import com.ai.aiagent.chat.ChatDtos.ChatRequest;
import com.ai.aiagent.chat.ChatDtos.ChatResponse;
import com.ai.aiagent.chat.ChatDtos.ChatStreamListener;
import com.ai.aiagent.chat.ChatDtos.RetrievalDebug;
import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.config.SecurityProperties;
import com.ai.aiagent.llm.EmbeddingService;
import com.ai.aiagent.llm.LlmClient;
import com.ai.aiagent.llm.LlmClientFactory;
import com.ai.aiagent.llm.LlmDtos.LlmRequest;
import com.ai.aiagent.llm.LlmDtos.LlmResponse;
import com.ai.aiagent.llm.LlmDtos.LlmUsage;
import com.ai.aiagent.llm.LlmDtos.StreamSink;
import com.ai.aiagent.llm.LlmProvider;
import com.ai.aiagent.observability.RagMetrics;
import com.ai.aiagent.rerank.Reranker;
import com.ai.aiagent.rerank.RerankerProvider;
import com.ai.aiagent.retrieval.HybridRetriever;
import com.ai.aiagent.retrieval.QueryPlanner;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.settings.RagSettingsService;
import com.ai.aiagent.store.ConversationRepository;
import com.ai.aiagent.store.StoreModels.Citation;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import com.ai.aiagent.store.StoreModels.Turn;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pipeline hoi-dap day du.
 *
 *   [0] Cache (exact + semantic)
 *   [1] Lich su hoi thoai (tu DB)
 *   [2] Sinh bien the truy van: cau goc + cau viet lai (+ HyDE)
 *   [3] Hybrid search song song + gop RRF + boost do moi
 *   [4] Rerank co diem
 *   [5] CONG TU CHOI - buoc moi, quyet dinh co tra loi hay khong
 *   [6] Dung prompt co ranh gioi chong prompt injection
 *   [7] Sinh cau tra loi (dong bo hoac stream)
 *   [8] Luu tin nhan + trich dan chi tiet + so token/chi phi
 */
@Service
@Slf4j
public class RagChatService {

    private final LlmClientFactory clients;
    private final RagSettingsService settings;
    private final QueryPlanner planner;
    private final HybridRetriever retriever;
    private final RerankerProvider rerankers;
    private final RelevanceGate gate;
    private final PromptBuilder prompts;
    private final ConversationRepository conversations;
    private final AnswerCacheService cache;
    private final EmbeddingService embeddings;
    private final RagProperties props;
    private final SecurityProperties securityProps;
    private final RagMetrics metrics;

    public RagChatService(LlmClientFactory clients, RagSettingsService settings,
                          QueryPlanner planner, HybridRetriever retriever,
                          RerankerProvider rerankers, RelevanceGate gate,
                          PromptBuilder prompts, ConversationRepository conversations,
                          AnswerCacheService cache, EmbeddingService embeddings,
                          RagProperties props, SecurityProperties securityProps,
                          RagMetrics metrics) {
        this.clients = clients;
        this.settings = settings;
        this.planner = planner;
        this.retriever = retriever;
        this.rerankers = rerankers;
        this.gate = gate;
        this.prompts = prompts;
        this.conversations = conversations;
        this.cache = cache;
        this.embeddings = embeddings;
        this.props = props;
        this.securityProps = securityProps;
        this.metrics = metrics;
    }

    // ================================================== Ban dong bo

    public ChatResponse answer(ChatRequest request, AccessScope scope) {
        long start = System.currentTimeMillis();
        metrics.recordQuestion();
        Prepared prepared = prepare(request, scope, null);

        if (prepared.cached() != null) {
            return finishFromCache(prepared, start);
        }
        if (prepared.decision().abstain()) {
            return finishAbstain(prepared, start);
        }

        long genStart = System.currentTimeMillis();
        LlmClient client = clients.get(prepared.provider(), prepared.model());
        LlmResponse generated = client.complete(
                new LlmRequest(prepared.prompt().system(), prepared.prompt().user(), null));
        metrics.recordGeneration(System.currentTimeMillis() - genStart);

        return finishGenerated(prepared, generated.text(), generated.usage(), start, null);
    }

    // ================================================== Ban stream

    /**
     * Stream cau tra loi.
     *
     * Day la thay doi lon nhat ve trai nghiem: truoc day nguoi dung ngoi truoc man
     * hinh trang 8-20 giay vi ca 3-4 loi goi LLM chay tuan tu roi moi tra ve mot cuc.
     * Gio ta bao tien do tung buoc, gui trich dan ngay khi chon xong nguon, roi
     * chay chu dan.
     */
    public void streamAnswer(ChatRequest request, AccessScope scope, ChatStreamListener listener) {
        long start = System.currentTimeMillis();
        metrics.recordQuestion();
        Prepared prepared;
        try {
            prepared = prepare(request, scope, listener);
        } catch (Exception e) {
            metrics.recordError();
            log.error("Loi khi chuan bi cau tra loi", e);
            listener.onError(safeMessage(e));
            return;
        }

        if (prepared.cached() != null) {
            listener.onStatus("cache", "Tra loi tu cache");
            listener.onCitations(prepared.cached().citations());
            listener.onToken(prepared.cached().answer());
            listener.onDone(finishFromCache(prepared, start));
            return;
        }
        if (prepared.decision().abstain()) {
            listener.onCitations(List.of());
            listener.onToken(prepared.decision().message());
            listener.onDone(finishAbstain(prepared, start));
            return;
        }

        listener.onCitations(prepared.citations());
        listener.onStatus("generating", "Đang tổng hợp câu trả lời từ "
                + prepared.citations().size() + " nguồn");

        LlmClient client;
        try {
            client = clients.get(prepared.provider(), prepared.model());
        } catch (Exception e) {
            metrics.recordError();
            log.error("Khong khoi tao duoc model sinh cau tra loi", e);
            listener.onError(e instanceof IllegalStateException
                    ? e.getMessage()
                    : "Không khởi tạo được model trả lời.");
            return;
        }
        long genStart = System.currentTimeMillis();
        StringBuilder buffer = new StringBuilder();

        client.stream(new LlmRequest(prepared.prompt().system(), prepared.prompt().user(), null),
                new StreamSink() {
                    @Override
                    public void onToken(String token) {
                        buffer.append(token);
                        listener.onToken(token);
                    }

                    @Override
                    public void onComplete(LlmResponse response) {
                        metrics.recordGeneration(System.currentTimeMillis() - genStart);
                        String text = response.text() == null || response.text().isEmpty()
                                ? buffer.toString() : response.text();
                        listener.onDone(finishGenerated(prepared, text, response.usage(), start, null));
                    }

                    @Override
                    public void onError(Throwable error) {
                        metrics.recordError();
                        log.error("Loi khi sinh cau tra loi", error);
                        // Da gui mot phan cho nguoi dung thi van luu lai phan do
                        if (buffer.length() > 0) {
                            finishGenerated(prepared, buffer.toString(), LlmUsage.EMPTY, start, "PARTIAL");
                        }
                        listener.onError(safeMessage(error));
                    }
                });
    }

    // ================================================== Pipeline chung

    /**
     * Trang thai trung gian dung chung cho ban dong bo va ban stream.
     *
     * CHU Y: khong giu {@link LlmClient} o day. Client duoc tao MUON, ngay truoc khi
     * sinh cau tra loi. Nho vay hai duong khong can den model sinh van chay binh
     * thuong khi chua cau hinh API key chat: duong TU CHOI TRA LOI va duong CACHE HIT.
     * Truoc khi sua, ca hai duong deu tra 500 vi client duoc tao ngay tu dau.
     */
    private record Prepared(
            ChatRequest request,
            AccessScope scope,
            String conversationId,
            LlmProvider provider,
            String model,
            float[] queryEmbedding,
            QueryPlanner.QueryPlan plan,
            HybridRetriever.RetrievalResult retrieval,
            Reranker.RerankResult rerank,
            RelevanceGate.Decision decision,
            PromptBuilder.BuiltPrompt prompt,
            List<Citation> citations,
            AnswerCacheService.CachedAnswer cached
    ) {
    }

    private Prepared prepare(ChatRequest request, AccessScope scope, ChatStreamListener listener) {
        String question = request.getQuestion() == null ? "" : request.getQuestion().strip();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("Cau hoi khong duoc de trong.");
        }
        int max = securityProps.getMaxQuestionLength();
        if (question.length() > max) {
            throw new IllegalArgumentException("Cau hoi qua dai (toi da " + max + " ky tu).");
        }

        RagSettingsService.ModelSelection defaults = settings.current();
        LlmProvider provider = Optional.ofNullable(LlmProvider.fromString(request.getProvider()))
                .orElse(defaults.provider());
        String model = request.getModel() != null && !request.getModel().isBlank()
                ? request.getModel().trim() : defaults.model();
        // Khong tao LlmClient o day - xem ghi chu tren record Prepared.

        if (props.getObservability().isLogQuestions()) {
            log.info(">>> CAU HOI [{}]: {} (conversation={}, category={})",
                    scope.clientId(), question, request.getConversationId(), request.getCategory());
        } else {
            log.info(">>> Cau hoi moi [{}] ({} ky tu, conversation={})",
                    scope.clientId(), question.length(), request.getConversationId());
        }

        String conversationId = request.getConversationId() != null
                && !request.getConversationId().isBlank()
                ? request.getConversationId().trim() : UUID.randomUUID().toString();

        // [0] Cache - nhung cau hoi truoc de dung cho ca cache semantic va vector search
        float[] queryEmbedding = null;
        if (embeddings.isReady()) {
            try {
                queryEmbedding = embeddings.embedOne(question);
            } catch (Exception e) {
                log.warn("Khong nhung duoc cau hoi ({}) -> bo qua cache semantic.", e.getMessage());
            }
        }

        if (request.cacheAllowed()) {
            Optional<AnswerCacheService.CachedAnswer> hit = cache.lookup(
                    question, scope, request.getCategory(), provider.name(), model, queryEmbedding);
            if (hit.isPresent()) {
                metrics.recordCacheHit(hit.get().kind());
                return new Prepared(request, scope, conversationId, provider, model,
                        queryEmbedding, null, null, null, RelevanceGate.Decision.proceed(),
                        null, hit.get().citations(), hit.get());
            }
        }

        // [1] Lich su
        status(listener, "history", "Đang đọc ngữ cảnh hội thoại");
        List<Turn> history = conversations.history(conversationId,
                props.getQueryRewrite().getMaxHistoryTurns());

        // [2] Bien the truy van (+ kiem tra do ro rang cua cau hoi)
        status(listener, "planning", "Đang làm rõ câu hỏi");
        QueryPlanner.QueryPlan plan = planner.plan(question, history);

        if (plan.clarifyingQuestion() != null && !plan.clarifyingQuestion().isBlank()) {
            // Cau hoi mo ho: bo qua hoan toan retrieval/rerank, hoi lai nguoi dung ngay
            RelevanceGate.Decision decision =
                    RelevanceGate.Decision.abstain("CLARIFICATION_NEEDED", plan.clarifyingQuestion());
            return new Prepared(request, scope, conversationId, provider, model,
                    queryEmbedding, plan, null, null, decision, null, List.of(), null);
        }

        // [3] Truy xuat
        status(listener, "retrieving", "Đang tìm trong tài liệu nội bộ");
        long retrievalStart = System.currentTimeMillis();
        HybridRetriever.RetrievalResult retrieval =
                retriever.retrieve(plan.variants(), scope, request.getCategory());
        metrics.recordRetrieval(System.currentTimeMillis() - retrievalStart);

        if (props.getObservability().isLogCandidates()) {
            logCandidates(retrieval.candidates());
        }

        // [4] Rerank
        Reranker reranker = rerankers.get();
        Reranker.RerankResult rerank;
        if (retrieval.isEmpty()) {
            rerank = Reranker.RerankResult.reliable(List.of(), reranker.name());
        } else {
            status(listener, "reranking",
                    "Đang chọn " + props.getRetrieval().getTopK() + " đoạn liên quan nhất trong "
                            + retrieval.candidates().size() + " ứng viên");
            long rerankStart = System.currentTimeMillis();
            rerank = reranker.rerank(plan.rewritten(), retrieval.candidates(),
                    props.getRetrieval().getTopK());
            metrics.recordRerank(System.currentTimeMillis() - rerankStart);
        }

        // [5] Cong tu choi
        RelevanceGate.Decision decision = gate.evaluate(retrieval, rerank);
        if (decision.abstain()) {
            return new Prepared(request, scope, conversationId, provider, model,
                    queryEmbedding, plan, retrieval, rerank, decision, null, List.of(), null);
        }

        // [6] Prompt + trich dan
        PromptBuilder.BuiltPrompt prompt = prompts.build(question, rerank.chunks());
        // Cong tac cua admin: tat thi khong tra danh sach trich dan ra client (van giu
        // nguyen cach LLM tu neu nguon trong van ban - do la chat luong cau tra loi,
        // khong phai tinh nang UI).
        List<Citation> citations = props.getChat().isCitationsEnabled()
                ? toCitations(prompt.sources()) : List.of();

        return new Prepared(request, scope, conversationId, provider, model,
                queryEmbedding, plan, retrieval, rerank, decision, prompt, citations, null);
    }

    // ================================================== Ket thuc

    private ChatResponse finishGenerated(Prepared p, String answer, LlmUsage usage,
                                         long start, String cacheHit) {
        long latency = System.currentTimeMillis() - start;
        metrics.recordTotal(latency);
        if (usage != null) {
            metrics.recordUsage(p.provider().name(), p.model(),
                    usage.inputTokens(), usage.outputTokens(), usage.costUsd());
        }
        if (props.getObservability().isLogAnswers()) {
            log.info("<<< TRA LOI: {}", answer);
        }

        Long messageId = persist(p, answer, usage, latency, false, cacheHit);

        if (p.request().cacheAllowed() && cacheHit == null) {
            cache.store(p.request().getQuestion().strip(), p.scope(), p.request().getCategory(),
                    p.provider().name(), p.model(), answer, p.citations(), p.queryEmbedding());
        }

        return new ChatResponse(answer, p.citations(), false, null, p.provider().name(), p.model(),
                usage == null ? LlmUsage.EMPTY : usage, latency, cacheHit, messageId,
                p.conversationId(), debugOf(p));
    }

    private ChatResponse finishAbstain(Prepared p, long start) {
        long latency = System.currentTimeMillis() - start;
        metrics.recordTotal(latency);
        metrics.recordAbstained(p.decision().reason());

        String message = p.decision().message();
        Long messageId = persist(p, message, LlmUsage.EMPTY, latency, true, null);

        return new ChatResponse(message, List.of(), true, p.decision().reason(),
                p.provider().name(), p.model(), LlmUsage.EMPTY, latency, null,
                messageId, p.conversationId(), debugOf(p));
    }

    private ChatResponse finishFromCache(Prepared p, long start) {
        long latency = System.currentTimeMillis() - start;
        metrics.recordTotal(latency);
        AnswerCacheService.CachedAnswer cached = p.cached();
        Long messageId = persist(p, cached.answer(), LlmUsage.EMPTY, latency, false, cached.kind());

        return new ChatResponse(cached.answer(), cached.citations(), false, null,
                cached.provider(), cached.model(), LlmUsage.EMPTY, latency, cached.kind(),
                messageId, p.conversationId(), null);
    }

    /** Luu hoi thoai. Loi ghi DB khong duoc lam mat cau tra loi cua nguoi dung. */
    private Long persist(Prepared p, String answer, LlmUsage usage, long latency,
                         boolean abstained, String cacheHit) {
        try {
            conversations.ensureConversation(p.conversationId(), p.scope().clientId(),
                    p.request().getCategory());
            String question = p.request().getQuestion().strip();
            conversations.updateTitleIfEmpty(p.conversationId(), question);
            conversations.appendUserMessage(p.conversationId(), question,
                    p.plan() == null ? null : (p.plan().wasRewritten() ? p.plan().rewritten() : null));

            long messageId = conversations.appendAssistantMessage(p.conversationId(), answer,
                    p.provider().name(), p.model(), usage, (int) latency, abstained, cacheHit);
            conversations.updateLatency(messageId, (int) latency);
            conversations.saveCitations(messageId, p.citations());
            return messageId;
        } catch (Exception e) {
            log.warn("Khong luu duoc hoi thoai (cau tra loi van duoc tra ve): {}", e.getMessage());
            return null;
        }
    }

    private List<Citation> toCitations(List<PromptBuilder.SourceRef> sources) {
        List<Citation> out = new ArrayList<>(sources.size());
        for (PromptBuilder.SourceRef ref : sources) {
            RetrievedChunk chunk = ref.chunk();
            double score = chunk.getRerankScore() >= 0 ? chunk.getRerankScore() : chunk.getRawScore();
            out.add(new Citation(
                    chunk.getId(),
                    chunk.getDocumentId(),
                    chunk.sourceLabel(),
                    chunk.getHeadingPath(),
                    snippet(chunk.getContent()),
                    Math.round(score * 1000) / 1000.0,
                    ref.number()));
        }
        return out;
    }

    /** Doan trich ngan de nguoi dung KIEM CHUNG duoc, khong chi co ten file nhu truoc. */
    private String snippet(String content) {
        if (content == null) return "";
        String flat = content.replaceAll("\\s+", " ").strip();
        return flat.length() <= 320 ? flat : flat.substring(0, 320) + "...";
    }

    private RetrievalDebug debugOf(Prepared p) {
        if (p.plan() == null || p.retrieval() == null) return null;
        return new RetrievalDebug(
                p.plan().wasRewritten() ? p.plan().rewritten() : null,
                p.plan().variants().size(),
                p.retrieval().vectorHits(),
                p.retrieval().fulltextHits(),
                p.retrieval().candidates().size(),
                p.rerank() == null ? 0 : p.rerank().chunks().size(),
                p.rerank() == null ? null : p.rerank().rerankerName(),
                p.rerank() != null && p.rerank().reliable(),
                Math.round(p.retrieval().bestRawScore() * 1000) / 1000.0,
                p.rerank() == null ? -1 : Math.round(p.rerank().bestScore() * 1000) / 1000.0);
    }

    private void status(ChatStreamListener listener, String stage, String detail) {
        if (listener != null) listener.onStatus(stage, detail);
    }

    private void logCandidates(List<RetrievedChunk> candidates) {
        int i = 1;
        for (RetrievedChunk c : candidates) {
            log.info("UNG VIEN [{}] rrf={} cosine={} | {} | {}", i++,
                    String.format("%.4f", c.getFusedScore()),
                    String.format("%.3f", c.getRawScore()),
                    c.getFileName(),
                    c.getContent().substring(0, Math.min(80, c.getContent().length())));
        }
    }

    /**
     * Khong bao gio de chi tiet loi noi bo ra ngoai - truoc day tra thang
     * {@code e.getMessage()} lam lo cau SQL, ten bang va host DB.
     */
    private String safeMessage(Throwable e) {
        if (e instanceof IllegalArgumentException || e instanceof SecurityException) {
            return e.getMessage();
        }
        return "Đã xảy ra lỗi khi xử lý câu hỏi. Vui lòng thử lại.";
    }
}
