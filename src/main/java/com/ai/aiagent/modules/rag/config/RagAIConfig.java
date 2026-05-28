package com.ai.aiagent.modules.rag.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagAIConfig {

    @Bean
    public EmbeddingModel ragEmbeddingModel() {
        return OllamaEmbeddingModel.builder()
                .baseUrl("http://localhost:11434")
                .modelName("nomic-embed-text")
                .build();
    }

    @Bean
    public EmbeddingStore<TextSegment> ragEmbeddingStore() {
        return PgVectorEmbeddingStore.builder()
                .host("localhost")
                .port(5432)
                .user("admin")
                .password("admin")
                .database("rag_db")
                .table("sharepoint_vectors")
                // Model nomic-embed-text sinh ra vector có độ dài 768 chiều
                .dimension(768)
                // Tự động tạo bảng và extension pgvector nếu chưa tồn tại
                .createTable(true)
                .build();
    }
}