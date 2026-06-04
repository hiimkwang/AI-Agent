package com.ai.aiagent.modules.rag.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RagAIConfig {

    @Value("${rag.openai.api-key}")
    private String openAiApiKey;
    @Value("${rag.openai.embedding-model}")
    private String embeddingModelName;
    @Value("${rag.openai.embedding-dimensions}")
    private int embeddingDimensions;

    /**
     * Embedding bằng OpenAI text-embedding-3.
     * LƯU Ý: model embedding của truy vấn và của tài liệu PHẢI giống nhau,
     * nên khi đổi model bắt buộc phải nạp (re-ingest) lại toàn bộ tài liệu.
     *
     * Việc lưu trữ vector giờ do {@code RagVectorRepository} đảm nhiệm (JdbcTemplate)
     * để hỗ trợ Hybrid Search, nên không còn bean EmbeddingStore của langchain4j ở đây.
     */
    @Bean
    public EmbeddingModel ragEmbeddingModel() {
        if (openAiApiKey == null || openAiApiKey.isBlank()) {
            throw new IllegalStateException(
                    "Chưa cấu hình OPENAI_API_KEY – cần thiết cho embedding text-embedding-3.");
        }
        return OpenAiEmbeddingModel.builder()
                .apiKey(openAiApiKey)
                .modelName(embeddingModelName)
                .dimensions(embeddingDimensions)
                .timeout(Duration.ofMinutes(2))
                .build();
    }
}
