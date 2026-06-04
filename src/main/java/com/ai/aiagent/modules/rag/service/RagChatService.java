package com.ai.aiagent.modules.rag.service;

import com.ai.aiagent.modules.rag.llm.ChatModelFactory;
import com.ai.aiagent.modules.rag.llm.LlmProvider;
import com.ai.aiagent.modules.rag.memory.ConversationMemory;
import com.ai.aiagent.modules.rag.rerank.RerankerProvider;
import com.ai.aiagent.modules.rag.retrieval.HybridRetriever;
import com.ai.aiagent.modules.rag.retrieval.QueryRewriter;
import com.ai.aiagent.modules.rag.settings.RagSettingsService;
import com.ai.aiagent.modules.rag.store.RetrievedChunk;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pipeline RAG đầy đủ cho mỗi câu hỏi:
 *
 *   [1] Viết lại câu hỏi (multi-turn, làm rõ ngữ cảnh)
 *   [2] Hybrid Search (vector + full-text + RRF)  -> ứng viên
 *   [3] Rerank (LLM hoặc Cohere)                  -> top-K
 *   [4] Mở rộng Parent (lấy đoạn cha, khử trùng)  -> context đủ ngữ cảnh
 *   [5] Sinh câu trả lời có trích nguồn
 *   [6] Lưu vào bộ nhớ hội thoại
 */
@Service
@Slf4j
public class RagChatService {

    private final ChatModelFactory chatModelFactory;
    private final RagSettingsService settingsService;
    private final QueryRewriter queryRewriter;
    private final HybridRetriever hybridRetriever;
    private final RerankerProvider rerankerProvider;
    private final ConversationMemory memory;

    @Value("${rag.retrieval.top-k}")
    private int topK;

    public RagChatService(ChatModelFactory chatModelFactory,
                          RagSettingsService settingsService,
                          QueryRewriter queryRewriter,
                          HybridRetriever hybridRetriever,
                          RerankerProvider rerankerProvider,
                          ConversationMemory memory) {
        this.chatModelFactory = chatModelFactory;
        this.settingsService = settingsService;
        this.queryRewriter = queryRewriter;
        this.hybridRetriever = hybridRetriever;
        this.rerankerProvider = rerankerProvider;
        this.memory = memory;
    }

    public record ChatResult(String answer, List<String> sources) {}

    /** Dùng cho Teams webhook: model mặc định, không có hội thoại. */
    public String retrieveAndAnswer(String userQuestion) {
        return answer(userQuestion, null, null, null, null).answer();
    }

    public ChatResult answer(String userQuestion, LlmProvider providerOverride,
                             String modelOverride, String conversationId) {
        return answer(userQuestion, providerOverride, modelOverride, conversationId, null);
    }

    /**
     * @param providerOverride provider riêng cho request (null = mặc định toàn cục)
     * @param modelOverride    model riêng cho request (null = mặc định của provider)
     * @param conversationId   id hội thoại để hỗ trợ multi-turn (null = không nhớ ngữ cảnh)
     * @param category         chỉ tìm trong nhóm tài liệu này (null = tìm toàn bộ)
     */
    public ChatResult answer(String userQuestion, LlmProvider providerOverride,
                             String modelOverride, String conversationId, String category) {
        log.info(">>> CÂU HỎI: {} (conversationId={}, category={})", userQuestion, conversationId, category);

        try {
            // Chọn model trả lời
            RagSettingsService.ModelSelection defaults = settingsService.getCurrent();
            LlmProvider provider = providerOverride != null ? providerOverride : defaults.getProvider();
            String modelName = (modelOverride != null && !modelOverride.isBlank())
                    ? modelOverride : defaults.getModel();
            ChatLanguageModel llm = chatModelFactory.get(provider, modelName);
            log.info("Model trả lời: provider={}, model={}", provider, modelName);

            // [1] Viết lại câu hỏi
            String searchQuery = queryRewriter.rewrite(conversationId, userQuestion);

            // [2] Hybrid retrieve (có thể lọc theo category)
            List<RetrievedChunk> candidates = hybridRetriever.retrieve(searchQuery, category);
            if (candidates.isEmpty()) {
                String msg = "Tôi không tìm thấy thông tin này trong tài liệu nội bộ.";
                memory.addUser(conversationId, userQuestion);
                memory.addAssistant(conversationId, msg);
                return new ChatResult(msg, List.of());
            }
            logCandidates(candidates);

            // [3] Rerank
            List<RetrievedChunk> top = rerankerProvider.get().rerank(searchQuery, candidates, topK);
            if (top.isEmpty()) {
                String msg = "Tôi không tìm thấy thông tin phù hợp trong tài liệu nội bộ.";
                memory.addUser(conversationId, userQuestion);
                memory.addAssistant(conversationId, msg);
                return new ChatResult(msg, List.of());
            }

            // [4] Mở rộng parent + khử trùng + thu thập nguồn
            String context = buildContext(top);
            List<String> sources = collectSources(top);

            // [5] Sinh câu trả lời
            String finalPrompt = buildPrompt(context, userQuestion);
            log.info("Gọi {} để tổng hợp câu trả lời...", provider);
            String generated = llm.generate(finalPrompt);
            log.info("<<< TRẢ LỜI: {}", generated);

            // [6] Lưu hội thoại
            memory.addUser(conversationId, userQuestion);
            memory.addAssistant(conversationId, generated);

            return new ChatResult(generated, sources);

        } catch (Exception e) {
            log.error("Lỗi khi xử lý câu hỏi: ", e);
            return new ChatResult("Đã xảy ra lỗi hệ thống khi truy vấn tài liệu: " + e.getMessage(), List.of());
        }
    }

    /** Ghép context từ các đoạn CHA (parent), khử trùng để không lặp đoạn. */
    private String buildContext(List<RetrievedChunk> top) {
        Set<String> seenParents = new LinkedHashSet<>();
        List<String> blocks = new ArrayList<>();
        for (RetrievedChunk c : top) {
            String parent = (c.getParentContent() != null && !c.getParentContent().isBlank())
                    ? c.getParentContent() : c.getContent();
            if (seenParents.add(parent)) {
                blocks.add("[Nguồn: " + c.getFileName() + "]\n" + parent);
            }
        }
        return String.join("\n\n---\n\n", blocks);
    }

    private List<String> collectSources(List<RetrievedChunk> top) {
        Set<String> sources = new LinkedHashSet<>();
        for (RetrievedChunk c : top) sources.add(c.getFileName());
        return new ArrayList<>(sources);
    }

    private void logCandidates(List<RetrievedChunk> candidates) {
        int i = 1;
        for (RetrievedChunk c : candidates) {
            String preview = c.getContent();
            preview = preview.substring(0, Math.min(100, preview.length()));
            log.info("ỨNG VIÊN [{}] rrf={} | nguồn={} | trích: {}",
                    i++, String.format("%.4f", c.getFusedScore()), c.getFileName(), preview);
        }
    }

    private String buildPrompt(String context, String userQuestion) {
        return """
                Bạn là trợ lý AI nội bộ. Trả lời câu hỏi CHỈ dựa trên phần TÀI LIỆU THAM KHẢO bên dưới.

                Quy tắc:
                - Trả lời bằng tiếng Việt, rõ ràng, đúng trọng tâm.
                - CHỈ dùng thông tin có trong tài liệu. KHÔNG bịa đặt, KHÔNG suy diễn ngoài tài liệu.
                - Nếu tài liệu không đủ thông tin, nói thẳng:
                  "Tôi không tìm thấy thông tin này trong tài liệu nội bộ."
                - Nêu rõ nguồn (tên file) cho thông tin bạn dùng.

                TÀI LIỆU THAM KHẢO:
                %s

                CÂU HỎI: %s

                TRẢ LỜI:
                """.formatted(context, userQuestion);
    }
}
