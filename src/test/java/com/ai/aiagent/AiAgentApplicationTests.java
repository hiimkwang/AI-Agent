package com.ai.aiagent;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Kiem tra Spring context nap duoc.
 *
 * TAT theo mac dinh: test nay can Postgres + pgvector that (Flyway chay migration
 * luc khoi dong context), nen neu bat thi `mvn test` se do tren may khong co DB va
 * tren CI chua cau hinh service container.
 *
 * Cach chay khi can: `docker compose up -d` roi
 * `mvn test -Dtest=AiAgentApplicationTests -DfailIfNoTests=false`
 * sau khi bo @Disabled, hoac dung Testcontainers (viec nen lam tiep).
 *
 * Cac test co gia tri thuc su nam o cac lop khong can DB:
 * {@link com.ai.aiagent.ingest.MarkdownChunkerTest},
 * {@link com.ai.aiagent.store.TsQueryBuilderTest},
 * {@link com.ai.aiagent.chat.RelevanceGateTest},
 * {@link com.ai.aiagent.chat.PromptBuilderTest}.
 */
@SpringBootTest
@Disabled("Can Postgres + pgvector dang chay; xem Javadoc.")
class AiAgentApplicationTests {

    @Test
    void contextLoads() {
    }
}
