package com.ai.aiagent.llm;

import com.ai.aiagent.config.RagProperties;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Locale;

/**
 * Dung mot {@link EmbeddingModel} tu cau hinh.
 *
 * Tach rieng khoi {@link EmbeddingService} vi tu khi co che THU NGHIEM MODEL
 * ({@code rag.embedding.trial.*}) thi he thong can dung HAI model cung luc: model dang
 * chay va model ung vien. Nhan ban doan switch provider sang cho khac la cach chac chan
 * de hai duong lech nhau - va lech o day nghia la vector cau hoi khac vector tai lieu,
 * loi im lang nhat trong ca he thong.
 */
@Slf4j
public final class EmbeddingModelFactory {

    /**
     * @param spec       provider + ten model + so chieu can dung
     * @param props      de lay API key / base URL dung chung
     * @param purposeLog nhan de ghi log ("chinh" hay "thu nghiem")
     * @return null neu thieu cau hinh bat buoc (vd chua co OPENAI_API_KEY)
     */
    public static EmbeddingModel build(Spec spec, RagProperties props, String purposeLog) {
        String provider = spec.provider() == null
                ? "OPENAI" : spec.provider().strip().toUpperCase(Locale.ROOT);
        try {
            return switch (provider) {
                case "LOCAL" -> {
                    log.info("Embedding [{}]: LOCAL all-MiniLM-L6-v2 (384 chieu)", purposeLog);
                    yield new AllMiniLmL6V2EmbeddingModel();
                }
                case "OLLAMA" -> {
                    log.info("Embedding [{}]: Ollama {} tai {} ({} chieu)", purposeLog,
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
                        log.error("Embedding [{}]: thieu OPENAI_API_KEY.", purposeLog);
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
                    log.info("Embedding [{}]: OpenAI {} ({} chieu)", purposeLog,
                            spec.model(), spec.dimensions());
                    yield builder.build();
                }
            };
        } catch (Exception e) {
            log.error("Khong khoi tao duoc model embedding [{}] ({}): {}",
                    purposeLog, provider, e.getMessage());
            return null;
        }
    }

    /** @param dimensions so chieu MONG DOI; voi LOCAL luon la 384 bat ke cau hinh */
    public record Spec(String provider, String model, int dimensions) {

        /** So chieu model thuc su sinh ra, de doi chieu voi cau hinh va voi schema. */
        public int actualDimensions() {
            return "LOCAL".equalsIgnoreCase(provider == null ? "" : provider.strip())
                    ? 384 : dimensions;
        }
    }

    private EmbeddingModelFactory() {
    }
}
