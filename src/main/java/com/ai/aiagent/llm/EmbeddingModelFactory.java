package com.ai.aiagent.llm;

import com.ai.aiagent.config.RagProperties;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Locale;

@Slf4j
public final class EmbeddingModelFactory {

    public static EmbeddingModel build(Spec spec, RagProperties props, String purposeLog) {
        String provider = spec.provider() == null
                ? "OPENAI" : spec.provider().strip().toUpperCase(Locale.ROOT);
        try {
            return switch (provider) {
                case "LOCAL" -> {
                    log.info("Embedding [{}]: LOCAL all-MiniLM-L6-v2 (384 dims)", purposeLog);
                    yield new AllMiniLmL6V2EmbeddingModel();
                }
                case "OLLAMA" -> {
                    log.info("Embedding [{}]: Ollama {} at {} ({} dims)", purposeLog,
                            spec.model(), props.getOllama().getBaseUrl(), spec.dimensions());
                    yield OllamaEmbeddingModel.builder()
                            .baseUrl(props.getOllama().getBaseUrl())
                            .modelName(spec.model())
                            .timeout(Duration.ofMinutes(3))
                            .build();
                }
                default -> {
                    String key = props.getOpenai().getApiKey();
                    if (key == null || key.isBlank()) {
                        log.error("Embedding [{}]: OPENAI_API_KEY is missing.", purposeLog);
                        yield null;
                    }
                    OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder =
                            OpenAiEmbeddingModel.builder()
                                    .apiKey(key)
                                    .modelName(spec.model())
                                    .dimensions(spec.dimensions())
                                    .timeout(Duration.ofMinutes(2));
                    if (props.getOpenai().getBaseUrl() != null
                            && !props.getOpenai().getBaseUrl().isBlank()) {
                        builder.baseUrl(props.getOpenai().getBaseUrl());
                    }
                    log.info("Embedding [{}]: OpenAI {} ({} dims)", purposeLog,
                            spec.model(), spec.dimensions());
                    yield builder.build();
                }
            };
        } catch (Exception e) {
            log.error("Could not initialise the embedding model [{}] ({}): {}",
                    purposeLog, provider, e.getMessage());
            return null;
        }
    }

    public record Spec(String provider, String model, int dimensions) {

        public int actualDimensions() {
            return "LOCAL".equalsIgnoreCase(provider == null ? "" : provider.strip())
                    ? 384 : dimensions;
        }
    }

    private EmbeddingModelFactory() {
    }
}
