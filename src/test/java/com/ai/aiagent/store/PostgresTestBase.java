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

/**
 * Nen chung cho cac test cham DB THAT (Postgres 17 + pgvector qua Testcontainers).
 *
 * Vi sao phai co: toan bo cau SQL tim kiem nam trong {@code ChunkRepository} va toan
 * bo schema nam trong 7 file Flyway - hai thu de hong nhat khi sua, va truoc ban nay
 * khong co mot test tu dong nao cham toi chung. Test nap context cu bi {@code @Disabled}
 * dung vi ly do "can DB that", tuc la cang sua nhieu cang khong duoc bao ve.
 *
 * TU BO QUA khi may khong co Docker, thay vi lam do ca lenh {@code mvn test}: mot bo
 * test khong chay duoc tren may nguoi khac se bi ho tat di, va nhu the con te hon la
 * khong co.
 *
 * Container dung CHUNG cho moi lop test ke thua (static, khoi tao mot lan) - dung
 * moi lop mot container thi thoi gian test tang gap nhieu lan ma khong duoc gi.
 */
@SpringBootTest(classes = PostgresTestBase.TestApp.class)
@ExtendWith(PostgresTestBase.DockerAvailable.class)
public abstract class PostgresTestBase {

    /**
     * Chi nap tang JDBC + Flyway va cac bean trong {@code store}, KHONG nap ca ung dung.
     *
     * Nap ca ung dung se keo theo cac bean can API key LLM, can Entra, can Bot... va
     * bien mot test ve tang luu tru thanh mot test ve cau hinh moi truong - do khong
     * chi cham hon ma con hong vi nhung ly do chang lien quan gi den thu dang kiem tra.
     */
    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.ComponentScan("com.ai.aiagent.store")
    @org.springframework.boot.autoconfigure.ImportAutoConfiguration({
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            JdbcTemplateAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration.class,
            FlywayAutoConfiguration.class})
    static class TestApp {
        /** Khong bat @ConfigurationProperties o day - test tu dat gia tri can thiet. */
        @org.springframework.context.annotation.Bean
        com.ai.aiagent.config.RagProperties ragProperties() {
            com.ai.aiagent.config.RagProperties props = new com.ai.aiagent.config.RagProperties();
            props.getEmbedding().setDimensions(4);
            return props;
        }
    }

    /**
     * Image pgvector chinh thuc - PHAI dung dung image nay chu khong phai postgres
     * thuan: schema V1 tao extension {@code vector}, khong co no thi migration do ngay
     * dong dau tien.
     */
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
        // So chieu duoc truyen vao DDL qua placeholder, giong luc chay that. Dung 4
        // chieu cho test nhanh - dieu QUAN TRONG la co che placeholder hoat dong,
        // khong phai con so cu the.
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

    /** Bo qua ca lop test khi may khong chay Docker. */
    public static class DockerAvailable implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return dockerAvailable()
                    ? ConditionEvaluationResult.enabled("Docker san sang")
                    : ConditionEvaluationResult.disabled(
                            "Bo qua: may nay khong chay Docker nen khong dung duoc Testcontainers.");
        }
    }

    /** Xoa sach du lieu giua cac test - moi test phai tu dung du lieu cua no. */
    protected void truncateAll() {
        jdbc.execute("""
                TRUNCATE rag_documents, rag_chunks, rag_conversations, rag_messages,
                         rag_message_citations, rag_feedback, rag_answer_cache,
                         rag_ingest_jobs, rag_audit_log
                RESTART IDENTITY CASCADE
                """);
    }
}
