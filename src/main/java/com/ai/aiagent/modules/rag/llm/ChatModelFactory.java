package com.ai.aiagent.modules.rag.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Nhà máy tạo ra {@link ChatLanguageModel} theo provider + tên model được yêu cầu.
 * Các model đã build sẽ được cache lại để tránh khởi tạo lại mỗi request.
 */
@Component
@Slf4j
public class ChatModelFactory {

    // --- Ollama ---
    @Value("${rag.ollama.base-url}")
    private String ollamaBaseUrl;
    @Value("${rag.ollama.chat-model}")
    private String ollamaDefaultModel;

    // --- OpenAI ---
    @Value("${rag.openai.api-key}")
    private String openAiApiKey;
    @Value("${rag.openai.chat-model}")
    private String openAiDefaultModel;

    // --- Gemini ---
    @Value("${rag.gemini.api-key}")
    private String geminiApiKey;
    @Value("${rag.gemini.chat-model}")
    private String geminiDefaultModel;

    private final Map<String, ChatLanguageModel> cache = new ConcurrentHashMap<>();

    /**
     * Lấy (hoặc tạo mới) một ChatLanguageModel.
     *
     * @param provider  nhà cung cấp; nếu null sẽ ném lỗi (caller phải resolve trước)
     * @param modelName tên model; nếu null/blank sẽ dùng model mặc định của provider
     */
    public ChatLanguageModel get(LlmProvider provider, String modelName) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider không được null.");
        }
        String resolvedModel = resolveModelName(provider, modelName);
        String cacheKey = provider + "::" + resolvedModel;
        return cache.computeIfAbsent(cacheKey, k -> build(provider, resolvedModel));
    }

    private String resolveModelName(LlmProvider provider, String modelName) {
        if (modelName != null && !modelName.isBlank()) {
            return modelName.trim();
        }
        return switch (provider) {
            case OLLAMA -> ollamaDefaultModel;
            case OPENAI -> openAiDefaultModel;
            case GEMINI -> geminiDefaultModel;
        };
    }

    private ChatLanguageModel build(LlmProvider provider, String modelName) {
        log.info("Khởi tạo ChatLanguageModel mới: provider={}, model={}", provider, modelName);
        return switch (provider) {
            case OLLAMA -> OllamaChatModel.builder()
                    .baseUrl(ollamaBaseUrl)
                    .modelName(modelName)
                    .temperature(0.2)
                    .numCtx(8192)
                    .timeout(Duration.ofMinutes(5))
                    .build();

            case OPENAI -> {
                requireKey(openAiApiKey, "OpenAI (OPENAI_API_KEY)");
                yield OpenAiChatModel.builder()
                        .apiKey(openAiApiKey)
                        .modelName(modelName)
                        .temperature(0.2)
                        .timeout(Duration.ofMinutes(2))
                        .build();
            }

            case GEMINI -> {
                requireKey(geminiApiKey, "Gemini (GEMINI_API_KEY)");
                yield new GeminiChatModel(geminiApiKey, modelName, 0.2);
            }
        };
    }

    private void requireKey(String key, String label) {
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Chưa cấu hình API key cho " + label
                    + ". Vui lòng đặt biến môi trường tương ứng.");
        }
    }
}
