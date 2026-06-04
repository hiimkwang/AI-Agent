package com.ai.aiagent.modules.rag.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cung cấp một ChatLanguageModel "rẻ" dùng cho các tác vụ nội bộ của pipeline:
 * viết lại câu hỏi (query rewrite), sinh ngữ cảnh chunk (contextual retrieval),
 * rerank bằng LLM, và chấm điểm khi đánh giá (eval judge).
 *
 * Tách riêng khỏi model trả lời (mà người dùng được chọn) để không lẫn lộn.
 */
@Component
public class InternalLlm {

    private final ChatModelFactory factory;

    @Value("${rag.internal.provider}")
    private String provider;
    @Value("${rag.internal.model}")
    private String model;

    public InternalLlm(ChatModelFactory factory) {
        this.factory = factory;
    }

    public ChatLanguageModel model() {
        return factory.get(LlmProvider.fromString(provider), model);
    }
}
