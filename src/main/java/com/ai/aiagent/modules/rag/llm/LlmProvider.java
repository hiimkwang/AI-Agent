package com.ai.aiagent.modules.rag.llm;

/**
 * Các nhà cung cấp mô hình Chat được hỗ trợ.
 * Dùng để chọn động lúc gọi request hoặc đặt mặc định qua Settings API.
 */
public enum LlmProvider {
    OLLAMA,
    OPENAI,
    GEMINI;

    public static LlmProvider fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LlmProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Provider không hợp lệ: '" + value
                    + "'. Chỉ chấp nhận: OLLAMA, OPENAI, GEMINI.");
        }
    }
}
