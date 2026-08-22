package com.ai.aiagent.store;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(classes = PostgresTestBase.TestApp.class)
@ExtendWith(PostgresTestBase.DockerAvailable.class)
public abstract class PostgresTestBase {

    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.ComponentScan("com.ai.aiagent.store")
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    static class TestApp {
        @org.springframework.context.annotation.Bean
        com.ai.aiagent.config.RagProperties ragProperties() {
            com.ai.aiagent.config.RagProperties props = new com.ai.aiagent.config.RagProperties();
            props.getEmbedding().setDimensions(4);
            return props;
        }
    }

    static final PostgreSQLContainer<?> POSTGRES;

    static {
        PostgreSQLContainer<?> container = null;
        if (dockerAvailable()) {
            container = new PostgreSQLContainer<>(
                    DockerImageName.parse("pgvector/pgvector:pg17")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("rag_test")
                    .withUsername("test")
                    .withPassword("test");
            container.start();
        }
        POSTGRES = container;
    }

    @Autowired
    protected JdbcTemplate jdbc;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGRES == null) return;
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.embeddingDim", () -> "4");
        registry.add("rag.embedding.dimensions", () -> "4");
    }

    static boolean dockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable e) {
            return false;
        }
    }

    public static class DockerAvailable implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return dockerAvailable()
                    ? ConditionEvaluationResult.enabled("Docker san sang")
                    : ConditionEvaluationResult.disabled(
                            "Bo qua: may nay khong chay Docker nen khong dung duoc Testcontainers.");
        }
    }

    protected void truncateAll() {
        jdbc.execute("""
                TRUNCATE rag_documents, rag_chunks, rag_conversations, rag_messages,
                         rag_message_citations, rag_feedback, rag_answer_cache,
                         rag_ingest_jobs, rag_audit_log
                RESTART IDENTITY CASCADE
                """);
    }
}
