package com.ai.aiagent.llm;

/** Cac nha cung cap mo hinh chat duoc ho tro. */
public enum LlmProvider {
    OPENAI,
    ANTHROPIC,
    GEMINI,
    OLLAMA;

    /** @return null neu {@code value} rong (nghia la "dung mac dinh toan cuc"). */
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
