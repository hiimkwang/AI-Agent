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
    private final SmallTalkDetector smallTalk;

    public RagChatService(LlmClientFactory clients, RagSettingsService settings,
                          QueryPlanner planner, HybridRetriever retriever,
                          RerankerProvider rerankers, RelevanceGate gate,
                          PromptBuilder prompts, ConversationRepository conversations,
                          AnswerCacheService cache, EmbeddingService embeddings,
                          RagProperties props, SecurityProperties securityProps,
                          RagMetrics metrics, SmallTalkDetector smallTalk) {
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
        this.smallTalk = smallTalk;
    }

    public ChatResponse answer(ChatRequest request, AccessScope scope) {
        return answer(request, scope, BotProfile.none());
    }

    public ChatResponse answer(ChatRequest request, AccessScope scope, BotProfile bot) {
        long start = System.currentTimeMillis();
        metrics.recordQuestion(bot.label());
        Prepared prepared = prepare(request, scope, null, bot);

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
        metrics.recordGeneration(System.currentTimeMillis() - genStart, bot.label());

        return finishGenerated(prepared, generated.text(), generated.usage(), start, null);
    }

    public void streamAnswer(ChatRequest request, AccessScope scope, ChatStreamListener listener) {
        long start = System.currentTimeMillis();
        BotProfile bot = BotProfile.none();
        metrics.recordQuestion(bot.label());
        Prepared prepared;
        try {
            prepared = prepare(request, scope, listener, bot);
        } catch (Exception e) {
            metrics.recordError(bot.label());
            log.error("Failed to prepare the answer", e);
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
            metrics.recordError(bot.label());
            log.error("Could not initialise the answering model", e);
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
                    public boolean cancelled() {
                        return listener.cancelled();
                    }

                    @Override
                    public void onToken(String token) {
                        if (listener.cancelled()) return;
                        buffer.append(token);
                        listener.onToken(token);
                    }

                    @Override
                    public void onComplete(LlmResponse response) {
                        metrics.recordGeneration(System.currentTimeMillis() - genStart, bot.label());
                        String text = response.text() == null || response.text().isEmpty()
                                ? buffer.toString() : response.text();
                        // Cau tra loi bi dung giua duong duoc luu de nguoi dung mo lai thay
                        // dung phan da doc, nhung KHONG duoc vao cache (moc != null).
                        String marker = listener.cancelled() ? "STOPPED" : null;
                        if (marker != null && text.isBlank()) return;
                        listener.onDone(finishGenerated(prepared, text, response.usage(), start, marker));
                    }

                    @Override
                    public void onError(Throwable error) {
                        metrics.recordError(bot.label());
                        log.error("Failed to generate the answer", error);
                        if (buffer.length() > 0) {
                            finishGenerated(prepared, buffer.toString(), LlmUsage.EMPTY, start, "PARTIAL");
                        }
                        listener.onError(safeMessage(error));
                    }
                });
    }

    private record Prepared(
            ChatRequest request,
            AccessScope scope,
            BotProfile bot,
            String conversationId,
            LlmProvider provider,
            String model,
            float[] queryEmbedding,
            String cacheContext,
            QueryPlanner.QueryPlan plan,
            HybridRetriever.RetrievalResult retrieval,
            Reranker.RerankResult rerank,
            RelevanceGate.Decision decision,
            PromptBuilder.BuiltPrompt prompt,
            List<Citation> citations,
            AnswerCacheService.CachedAnswer cached
    ) {
    }

    private Prepared prepare(ChatRequest request, AccessScope scope, ChatStreamListener listener,
                             BotProfile bot) {
        String question = request.getQuestion() == null ? "" : request.getQuestion().strip();
        if (question.isEmpty()) {
            throw new IllegalArgumentException("Cau hoi khong duoc de trong.");
        }
        int max = securityProps.getMaxQuestionLength();
        if (question.length() > max) {
            throw new IllegalArgumentException("Cau hoi qua dai (toi da " + max + " ky tu).");
        }

        RagSettingsService.ModelSelection defaults = settings.current();
        /* Chi ADMIN duoc ep provider/model. Neu khong, mot nguoi dung thuong goi API
           tay la chon duoc model dat nhat va tieu tien cua ca he thong. An o giao dien
           thoi thi khong phai chan. */
        boolean mayPickModel = scope.isAdmin();
        LlmProvider provider = firstNonNull(
                mayPickModel ? LlmProvider.fromString(request.getProvider()) : null,
                LlmProvider.fromString(bot.provider()),
                defaults.provider());
        String model = firstNonBlank(
                mayPickModel ? request.getModel() : null, bot.model(), defaults.model());
        if (!mayPickModel && (request.getProvider() != null || request.getModel() != null)) {
            log.debug("Ignoring client-supplied provider/model for {}: caller is not ADMIN",
                    scope.clientId());
        }

        if (props.getObservability().isLogQuestions()) {
            log.info("Question from {}: {} (conversation={}, category={})",
                    scope.clientId(), question, request.getConversationId(), request.getCategory());
        } else {
            log.info("Question from {} ({} chars, conversation={})",
                    scope.clientId(), question.length(), request.getConversationId());
        }

        String conversationId = request.getConversationId() != null
                && !request.getConversationId().isBlank()
                ? request.getConversationId().trim() : UUID.randomUUID().toString();

        // Answered before retrieval on purpose: a greeting matches no document, so the
        // relevance gate would reply "khong tim thay tai lieu" to "chao ban".
        if (smallTalk.matches(question)) {
            log.debug("Small talk detected, answering with the greeting without retrieval.");
            RelevanceGate.Decision decision =
                    RelevanceGate.Decision.abstain("SMALL_TALK", smallTalk.reply());
            return new Prepared(request, scope, bot, conversationId, provider, model,
                    null, null, null, null, null, decision, null, List.of(), null);
        }

        // Read before the cache lookup, not after: the cache key depends on it. A follow-up
        // means something different in every conversation.
        status(listener, "history", "Đang đọc ngữ cảnh hội thoại");
        List<Turn> history = conversations.history(conversationId,
                Math.max(props.getQueryRewrite().getMaxHistoryTurns(),
                        props.getChat().getHistoryTurns()));
        String cacheContext = AnswerCacheService.contextFingerprint(history);

        float[] queryEmbedding = null;
        if (embeddings.isReady()) {
            try {
                queryEmbedding = embeddings.embedOne(question);
            } catch (Exception e) {
                log.warn("Could not embed the question ({}), skipping the semantic cache.", e.getMessage());
            }
        }

        if (request.cacheAllowed()) {
            Optional<AnswerCacheService.CachedAnswer> hit = cache.lookup(
                    question, scope, request.getCategory(), provider.name(), model,
                    queryEmbedding, cacheContext);
            if (hit.isPresent()) {
                metrics.recordCacheHit(hit.get().kind(), bot.label());
                return new Prepared(request, scope, bot, conversationId, provider, model,
                        queryEmbedding, cacheContext, null, null, null,
                        RelevanceGate.Decision.proceed(), null, hit.get().citations(), hit.get());
            }
        }

        status(listener, "planning", "Đang làm rõ câu hỏi");
        QueryPlanner.QueryPlan plan = planner.plan(question, history);

        if (plan.clarifyingQuestion() != null && !plan.clarifyingQuestion().isBlank()) {
            RelevanceGate.Decision decision =
                    RelevanceGate.Decision.abstain("CLARIFICATION_NEEDED", plan.clarifyingQuestion());
            return new Prepared(request, scope, bot, conversationId, provider, model,
                    queryEmbedding, cacheContext, plan, null, null, decision, null, List.of(), null);
        }

        status(listener, "retrieving", "Đang tìm trong tài liệu nội bộ");
        long retrievalStart = System.currentTimeMillis();
        HybridRetriever.RetrievalResult retrieval =
                retriever.retrieve(plan.variants(), scope, request.getCategory());
        metrics.recordRetrieval(System.currentTimeMillis() - retrievalStart, bot.label());

        if (props.getObservability().isLogCandidates()) {
            logCandidates(retrieval.candidates());
        }

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
            metrics.recordRerank(System.currentTimeMillis() - rerankStart, bot.label());
        }

        RelevanceGate.Decision decision = gate.evaluate(retrieval, rerank);
        if (decision.abstain()) {
            return new Prepared(request, scope, bot, conversationId, provider, model,
                    queryEmbedding, cacheContext, plan, retrieval, rerank, decision, null,
                    List.of(), null);
        }

        PromptBuilder.BuiltPrompt prompt = prompts.build(
                question, plan.rewritten(), history, rerank.chunks(), bot.personaPrompt());
        List<Citation> citations = props.getChat().isCitationsEnabled()
                ? toCitations(prompt.sources()) : List.of();

        return new Prepared(request, scope, bot, conversationId, provider, model,
                queryEmbedding, cacheContext, plan, retrieval, rerank, decision, prompt,
                citations, null);
    }

    public static final String DEFAULT_NO_ANSWER_FOLLOW_UP =
            "Bạn thử hỏi cụ thể hơn giúp mình nhé — ví dụ nêu rõ tên tài liệu, nghiệp vụ, hoặc "
                    + "từ khoá liên quan. Cũng có thể nội dung này chưa được nạp vào hệ thống.";

    private ChatResponse finishGenerated(Prepared p, String rawAnswer, LlmUsage usage,
                                         long start, String cacheHit) {
        String answer = verifyCitations(p, rawAnswer);
        long latency = System.currentTimeMillis() - start;
        metrics.recordTotal(latency, p.bot().label());
        if (usage != null) {
            metrics.recordUsage(p.provider().name(), p.model(),
                    usage.inputTokens(), usage.outputTokens(), usage.costUsd(), p.bot().label());
        }
        if (props.getObservability().isLogAnswers() && log.isDebugEnabled()) {
            log.debug("Answer: {}", answer);
        }

        // The gate can let passages through and the model still decide they fall short. To
        // the person asking that is the same outcome as an abstention, so it gets the same
        // treatment: drop the sources (six of them listed under "I found nothing" reads as a
        // bug - that is exactly what the screenshot showed), offer topics that really are in
        // the corpus, and ask a question back when there is nothing concrete to offer.
        boolean refused = cacheHit == null && cache.looksLikeRefusal(answer);
        List<String> suggestions = List.of();
        if (refused) {
            suggestions = abstainSuggestions(p);
            if (suggestions.isEmpty()) {
                answer = answer.strip() + "\n\n" + noAnswerFollowUp();
            }
            metrics.recordAbstained("LLM_FOUND_NOTHING", p.bot().label());
            log.info("Model reported nothing found after {} candidate(s); returning {} suggestion(s).",
                    p.retrieval() == null ? 0 : p.retrieval().candidates().size(), suggestions.size());
        }
        List<Citation> citations = refused ? List.of() : p.citations();

        Long messageId = persist(p, answer, usage, latency, refused, cacheHit);

        if (p.request().cacheAllowed() && cacheHit == null) {
            cache.store(p.request().getQuestion().strip(), p.scope(), p.request().getCategory(),
                    p.provider().name(), p.model(), answer, p.citations(), p.queryEmbedding(),
                    p.cacheContext());
        }

        return new ChatResponse(answer, citations, refused,
                refused ? "LLM_FOUND_NOTHING" : null, p.provider().name(), p.model(),
                usage == null ? LlmUsage.EMPTY : usage, latency, cacheHit, messageId,
                p.conversationId(), debugOf(p), suggestions);
    }

    private String noAnswerFollowUp() {
        String configured = props.getChat().getNoAnswerFollowUp();
        return configured == null || configured.isBlank()
                ? DEFAULT_NO_ANSWER_FOLLOW_UP : configured.strip();
    }

    private ChatResponse finishAbstain(Prepared p, long start) {
        long latency = System.currentTimeMillis() - start;
        metrics.recordTotal(latency, p.bot().label());
        metrics.recordAbstained(p.decision().reason(), p.bot().label());

        String message = p.decision().message();
        Long messageId = persist(p, message, LlmUsage.EMPTY, latency, true, null);

        return new ChatResponse(message, List.of(), true, p.decision().reason(),
                p.provider().name(), p.model(), LlmUsage.EMPTY, latency, null,
                messageId, p.conversationId(), debugOf(p), abstainSuggestions(p));
    }

    /**
     * Cau hoi goi y cho luc he thong tu choi tra loi. Hai nguon khac nhau, vi hai tinh
     * huong xay ra o hai thoi diem khac nhau cua pipeline:
     *
     * - CLARIFICATION_NEEDED: chua truy xuat gi ca, nen lay tu QueryPlanner - no vua goi
     *   model de danh gia do ro rang va doan luon 2-3 cau cu the, khong ton them loi goi.
     * - Cac ly do con lai (khong tim thay / diem qua thap): DA truy xuat, nen lay tu
     *   heading cua chinh cac doan ung vien. Do la chu de CO THAT trong kho, nen bam vao
     *   la truy xuat trung - khac hoan toan voi viec ghep ten nhom (slug) thanh cau hoi.
     */
    private List<String> abstainSuggestions(Prepared p) {
        if ("CLARIFICATION_NEEDED".equals(p.decision().reason())) {
            return p.plan() == null ? List.of() : p.plan().suggestions();
        }
        if (p.retrieval() == null || p.retrieval().candidates().isEmpty()) return List.of();

        List<String> out = new ArrayList<>();
        for (RetrievedChunk c : p.retrieval().candidates()) {
            String topic = mostSpecificTopic(c);
            if (topic == null) continue;
            if (out.stream().noneMatch(x -> x.equalsIgnoreCase(topic))) out.add(topic);
            if (out.size() == 3) break;
        }
        return List.copyOf(out);
    }

    /**
     * Doan cuoi cua heading path la chu de cu the nhat; khong co thi lay ten file.
     *
     * <p>These become clickable chips under "Co the ban muon hoi", so a raw heading fragment is
     * not good enough: headings arrive with trailing colons, bare record ids, and numbering, and
     * "Phan quyen su dung bao cao:" does not read as a question anyone would type.
     */
    private static String mostSpecificTopic(RetrievedChunk c) {
        String heading = c.getHeadingPath();
        if (heading != null && !heading.isBlank()) {
            String[] parts = heading.split(">");
            String cleaned = tidyTopic(parts[parts.length - 1]);
            if (cleaned != null) return cleaned;
        }
        String file = c.getFileName();
        if (file == null || file.isBlank()) return null;
        int dot = file.lastIndexOf('.');
        return tidyTopic(dot > 0 ? file.substring(0, dot) : file);
    }

    /** Null when the fragment would not read as a topic a person might ask about. */
    private static String tidyTopic(String raw) {
        if (raw == null) return null;
        String t = raw.strip()
                // Leading section numbering: "2.1.3 Dat lenh" -> "Dat lenh".
                .replaceFirst("^\\d+(?:\\.\\d+)*\\.?\\s+", "")
                // Trailing punctuation left behind by the heading, and any trailing id.
                .replaceAll("[\\s:;.,\\-–—]+$", "")
                .replaceAll("\\s*:\\s*\\d{3,}$", "")
                .strip();
        if (t.length() < 8 || t.length() > 80) return null;

        long letters = t.chars().filter(Character::isLetter).count();
        // Mostly digits or codes: "310005", "DO09 - 12345".
        if (letters < t.length() * 0.6) return null;
        // A single word is too vague to be worth a chip.
        return t.contains(" ") ? t : null;
    }

    private ChatResponse finishFromCache(Prepared p, long start) {
        long latency = System.currentTimeMillis() - start;
        metrics.recordTotal(latency, p.bot().label());
        AnswerCacheService.CachedAnswer cached = p.cached();
        Long messageId = persist(p, cached.answer(), LlmUsage.EMPTY, latency, false, cached.kind());

        return new ChatResponse(cached.answer(), cached.citations(), false, null,
                cached.provider(), cached.model(), LlmUsage.EMPTY, latency, cached.kind(),
                messageId, p.conversationId(), null, List.of());
    }

    private Long persist(Prepared p, String answer, LlmUsage usage, long latency,
                         boolean abstained, String cacheHit) {
        try {
            conversations.ensureConversation(p.conversationId(), p.scope().clientId(),
                    p.request().getCategory(), p.bot().id());
            String question = p.request().getQuestion().strip();
            conversations.updateTitleIfEmpty(p.conversationId(), question);
            conversations.appendUserMessage(p.conversationId(), question,
                    p.plan() == null ? null : (p.plan().wasRewritten() ? p.plan().rewritten() : null),
                    p.bot().slug());

            long messageId = conversations.appendAssistantMessage(p.conversationId(), answer,
                    p.provider().name(), p.model(), usage, (int) latency, abstained, cacheHit,
                    p.bot().slug());
            conversations.updateLatency(messageId, (int) latency);
            conversations.saveCitations(messageId, p.citations());
            return messageId;
        } catch (Exception e) {
            log.warn("Could not persist the conversation, answer still returned: {}", e.getMessage());
            return null;
        }
    }

    private String verifyCitations(Prepared p, String answer) {
        if (p.prompt() == null || answer == null) return answer;
        try {
            PromptBuilder.CitationCheck check =
                    PromptBuilder.verifyCitations(answer, p.prompt().sources().size());
            if (check.hadInvalid()) {
                metrics.recordInvalidCitation(p.bot().label());
                log.warn("Answer cited {} source(s) that do not exist (only {} available), markers stripped.",
                        check.invalid(), p.prompt().sources().size());
            }
            return check.answer();
        } catch (Exception e) {
            log.warn("Citation check failed ({}), answer left untouched.", e.getMessage());
            return answer;
        }
    }

    private List<Citation> toCitations(List<PromptBuilder.SourceRef> sources) {
        List<Citation> out = new ArrayList<>(sources.size());
        for (PromptBuilder.SourceRef ref : sources) {
            RetrievedChunk chunk = ref.chunk();
            double score = chunk.getRerankScore() >= 0 ? chunk.getRerankScore()
                    : (chunk.getCosine() == null ? 0.0 : chunk.getCosine());
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
                Math.round(p.retrieval().bestCosine() * 1000) / 1000.0,
                p.rerank() == null ? -1 : Math.round(p.rerank().bestScore() * 1000) / 1000.0);
    }

    private void status(ChatStreamListener listener, String stage, String detail) {
        if (listener != null) listener.onStatus(stage, detail);
    }

    /** One line per retrieved chunk: only ever useful when chasing a bad answer. */
    private void logCandidates(List<RetrievedChunk> candidates) {
        if (!log.isDebugEnabled()) return;
        int i = 1;
        for (RetrievedChunk c : candidates) {
            log.debug("Candidate {}: rrf={} cosine={} file={} text={}", i++,
                    String.format("%.4f", c.getFusedScore()),
                    String.format("%.3f", c.getCosine() == null ? 0.0 : c.getCosine()),
                    c.getFileName(),
                    c.getContent().substring(0, Math.min(80, c.getContent().length())));
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T v : values) {
            if (v != null) return v;
        }
        return null;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.strip();
        }
        return null;
    }

    private String safeMessage(Throwable e) {
        if (e instanceof IllegalArgumentException || e instanceof SecurityException) {
            return e.getMessage();
        }
        return "Đã xảy ra lỗi khi xử lý câu hỏi. Vui lòng thử lại.";
    }
}
