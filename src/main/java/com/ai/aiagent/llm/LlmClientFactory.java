package com.ai.aiagent.llm;

import com.ai.aiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class LlmClientFactory {

    private final RagProperties props;
    private final Map<String, LlmClient> cache = new ConcurrentHashMap<>();

    public LlmClientFactory(RagProperties props) {
        this.props = props;
    }

    public void clearCache() {
        cache.clear();
        log.info("LlmClient cache cleared; the next call rebuilds clients from current config.");
    }

    public LlmClient get(LlmProvider provider, String model) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider khong duoc null.");
        }
        String resolved = resolveModel(provider, model);
        return cache.computeIfAbsent(provider + "::" + resolved, k -> build(provider, resolved));
    }

    public String resolveModel(LlmProvider provider, String model) {
        if (model != null && !model.isBlank()) return model.trim();
        return switch (provider) {
            case OPENAI -> props.getOpenai().getChatModel();
            case ANTHROPIC -> props.getAnthropic().getChatModel();
            case GEMINI -> props.getGemini().getChatModel();
            case OLLAMA -> props.getOllama().getChatModel();
        };
    }

    public boolean isAvailable(LlmProvider provider) {
        return switch (provider) {
            case OPENAI -> hasText(props.getOpenai().getApiKey());
            case ANTHROPIC -> hasText(props.getAnthropic().getApiKey());
            case GEMINI -> hasText(props.getGemini().getApiKey());
            case OLLAMA -> hasText(props.getOllama().getBaseUrl());
        };
    }

    public List<Map<String, Object>> catalog() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.add(entry(LlmProvider.ANTHROPIC, List.of(
                "claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5")));
        out.add(entry(LlmProvider.OPENAI, List.of(
                "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1")));
        out.add(entry(LlmProvider.GEMINI, List.of(
                "gemini-2.0-flash", "gemini-1.5-flash", "gemini-1.5-pro")));
        out.add(entry(LlmProvider.OLLAMA, List.of(
                "qwen2.5:7b", "qwen2.5:14b", "llama3.1:8b")));
        return out;
    }

    private Map<String, Object> entry(LlmProvider provider, List<String> models) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("provider", provider.name());
        m.put("available", isAvailable(provider));
        m.put("models", models);
        m.put("defaultModel", resolveModel(provider, null));
        return m;
    }

    private LlmClient build(LlmProvider provider, String model) {
        log.info("Created a new LlmClient: provider={}, model={}", provider, model);
        RagProperties.Llm llm = props.getLlm();
        return switch (provider) {
            case OPENAI -> {
                requireKey(props.getOpenai().getApiKey(), "OpenAI (OPENAI_API_KEY)");
                yield new OpenAiLlmClient(
                        props.getOpenai().getApiKey(),
                        props.getOpenai().getBaseUrl(),
                        model,
                        llm.getTemperature(),
                        llm.getMaxOutputTokens(),
                        llm.getTimeoutSeconds());
            }
            case ANTHROPIC -> {
                requireKey(props.getAnthropic().getApiKey(), "Anthropic (ANTHROPIC_API_KEY)");
                yield new AnthropicLlmClient(
                        props.getAnthropic().getApiKey(),
                        model,
                        llm.getMaxOutputTokens(),
                        props.getAnthropic().isThinkingEnabled(),
                        props.getAnthropic().getEffort(),
                        llm.getTimeoutSeconds());
            }
            case GEMINI -> {
                requireKey(props.getGemini().getApiKey(), "Gemini (GEMINI_API_KEY)");
                yield new GeminiLlmClient(
                        props.getGemini().getApiKey(),
                        model,
                        llm.getTemperature(),
                        llm.getMaxOutputTokens(),
                        llm.getTimeoutSeconds());
            }
            case OLLAMA -> new OllamaLlmClient(
                    props.getOllama().getBaseUrl(),
                    model,
                    llm.getTemperature(),
                    llm.getTimeoutSeconds());
        };
    }

    private void requireKey(String key, String label) {
        if (!hasText(key)) {
            throw new IllegalStateException("Chua cau hinh API key cho " + label
                    + ". Dat bien moi truong tuong ung roi khoi dong lai.");
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
