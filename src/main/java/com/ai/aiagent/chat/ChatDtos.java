package com.ai.aiagent.chat;

import com.ai.aiagent.llm.LlmDtos.LlmUsage;
import com.ai.aiagent.store.StoreModels.Citation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class ChatDtos {

    private ChatDtos() {
    }

    public static class ChatRequest {
        @NotBlank(message = "Cau hoi khong duoc de trong")
        @Size(max = 4000, message = "Cau hoi qua dai (toi da 4000 ky tu)")
        private String question;
        private String provider;
        private String model;
        private String conversationId;
        private String category;
        private Boolean useCache;

        public String getQuestion() { return question; }
        public void setQuestion(String question) { this.question = question; }
        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String getConversationId() { return conversationId; }
        public void setConversationId(String conversationId) { this.conversationId = conversationId; }
        public String getCategory() { return category; }
        public void setCategory(String category) { this.category = category; }
        public Boolean getUseCache() { return useCache; }
        public void setUseCache(Boolean useCache) { this.useCache = useCache; }

        public boolean cacheAllowed() {
            return useCache == null || useCache;
        }
    }

    public record RetrievalDebug(
            String rewrittenQuery,
            int queryVariants,
            int vectorHits,
            int fulltextHits,
            int candidates,
            int selected,
            String reranker,
            boolean rerankReliable,
            double bestVectorScore,
            double bestRerankScore
    ) {
    }

    public record ChatResponse(
            String answer,
            List<Citation> citations,
            boolean abstained,
            String abstainReason,
            String provider,
            String model,
            LlmUsage usage,
            long latencyMs,
            String cacheHit,
            Long messageId,
            String conversationId,
            RetrievalDebug debug,
            /** Cau hoi goi y, chi co khi he thong tu choi tra loi. */
            List<String> suggestions
    ) {
    }

    public interface ChatStreamListener {
        void onStatus(String stage, String detail);

        void onCitations(List<Citation> citations);

        void onToken(String token);

        void onDone(ChatResponse response);

        void onError(String message);

        /** True khi client da ngat ket noi (bam "dung" hoac dong tab). */
        default boolean cancelled() {
            return false;
        }
    }
}
