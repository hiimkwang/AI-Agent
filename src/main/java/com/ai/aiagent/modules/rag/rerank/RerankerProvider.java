package com.ai.aiagent.modules.rag.rerank;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Chọn bộ rerank theo cấu hình {@code rag.rerank.provider} (LLM | COHERE).
 * Nếu chọn COHERE nhưng chưa có API key thì tự động fallback về LLM.
 */
@Component
@Slf4j
public class RerankerProvider {

    private final LlmReranker llmReranker;
    private final CohereReranker cohereReranker;

    @Value("${rag.rerank.provider}")
    private String provider;

    public RerankerProvider(LlmReranker llmReranker, CohereReranker cohereReranker) {
        this.llmReranker = llmReranker;
        this.cohereReranker = cohereReranker;
    }

    public Reranker get() {
        if ("COHERE".equalsIgnoreCase(provider)) {
            if (cohereReranker.isAvailable()) {
                return cohereReranker;
            }
            log.warn("Cấu hình rerank=COHERE nhưng thiếu COHERE_API_KEY -> dùng LLM rerank.");
        }
        return llmReranker;
    }
}
