package com.ai.aiagent.llm;

public enum LlmProvider {
    OPENAI,
    ANTHROPIC,
    GEMINI,
    OLLAMA;

    public static LlmProvider fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LlmProvider.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Provider khong hop le: '" + value
                    + "'. Chi chap nhan: OPENAI, ANTHROPIC, GEMINI, OLLAMA.");
        }
    }
}
