package com.ai.aiagent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RagMetricsTest {

    private SimpleMeterRegistry registry;
    private RagMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new RagMetrics(registry);
    }

    @Test
    @DisplayName("Cau hoi cua tung bot vao tung chuoi so lieu rieng")
    void questionsAreSplitPerBot() {
        metrics.recordQuestion("nhan-su");
        metrics.recordQuestion("nhan-su");
        metrics.recordQuestion("phap-che");

        assertThat(counter("rag.questions", "nhan-su")).isEqualTo(2.0);
        assertThat(counter("rag.questions", "phap-che")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Bot rong/null duoc ghi la 'web' chu khong phai the rong")
    void missingBotBecomesWeb() {
        metrics.recordQuestion(null);
        metrics.recordQuestion("");

        assertThat(counter("rag.questions", RagMetrics.WEB)).isEqualTo(2.0);
        assertThat(registry.find("rag.questions").tag("bot", "").counter()).isNull();
    }

    @Test
    @DisplayName("Snapshot cong don MOI bot, khong phai chi bot dau tien")
    void snapshotSumsAcrossBots() {
        metrics.recordQuestion("nhan-su");
        metrics.recordQuestion("phap-che");
        metrics.recordQuestion(RagMetrics.WEB);
        metrics.recordAbstained("NO_RELEVANT_CHUNK", "phap-che");

        Map<String, Object> snapshot = metrics.snapshot();

        assertThat(snapshot.get("questions")).isEqualTo(3L);
        assertThat(snapshot.get("abstained")).isEqualTo(1L);
        assertThat(snapshot.get("abstainRate")).isEqualTo(33.3);
    }

    @Test
    @DisplayName("Cache hit van tach duoc exact/semantic sau khi them the bot")
    void cacheHitsKeepTheirKind() {
        metrics.recordCacheHit("EXACT", "nhan-su");
        metrics.recordCacheHit("SEMANTIC", "nhan-su");
        metrics.recordCacheHit("SEMANTIC", "phap-che");

        Map<String, Object> snapshot = metrics.snapshot();

        assertThat(snapshot.get("cacheHitsExact")).isEqualTo(1L);
        assertThat(snapshot.get("cacheHitsSemantic")).isEqualTo(2L);
    }

    @Test
    @DisplayName("Ly do tu choi giu ca the reason lan the bot")
    void abstainReasonKeepsBothTags() {
        metrics.recordAbstained("LOW_SCORE", "phap-che");

        Counter c = registry.find("rag.abstain.reason")
                .tag("reason", "LOW_SCORE").tag("bot", "phap-che").counter();
        assertThat(c).isNotNull();
        assertThat(c.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Chi phi vua cong vao tong, vua tach duoc theo bot")
    void costIsBothAggregatedAndSplit() {
        metrics.recordUsage("ANTHROPIC", "claude", 100, 50, 0.002, "nhan-su");
        metrics.recordUsage("ANTHROPIC", "claude", 200, 20, 0.003, "phap-che");

        Map<String, Object> snapshot = metrics.snapshot();
        assertThat(snapshot.get("inputTokens")).isEqualTo(300L);
        assertThat((Double) snapshot.get("costUsd")).isEqualTo(0.005);

        assertThat(counter("rag.cost.usd", "nhan-su")).isEqualTo(0.002);
        assertThat(counter("rag.cost.usd", "phap-che")).isEqualTo(0.003);
    }

    @Test
    @DisplayName("Chi phi theo bot phai SONG SOT khi doi sang ten cua Prometheus")
    // SimpleMeterRegistry does not apply Prometheus naming, so this only reproduces against
    // the real registry: a gauge named rag.cost.usd.total collides with the rag.cost.usd
    // counter (Micrometer appends _total), and the counter is dropped with just a warning.
    void costPerBotSurvivesPrometheusNaming() {
        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        RagMetrics prometheusMetrics = new RagMetrics(prom);

        prometheusMetrics.recordUsage("OPENAI", "gpt-4o-mini", 10, 5, 0.002, "nhan-su");
        prometheusMetrics.recordUsage("OPENAI", "gpt-4o-mini", 20, 8, 0.003, "phap-che");

        // Trieu chung that: chuoi so lieu bien mat khoi ban scrape.
        String scrape = prom.scrape();
        assertThat(scrape)
                .as("ban scrape khong co rag_cost_usd_total")
                .contains("rag_cost_usd_total");
        assertThat(scrape)
                .as("mat nhan bot -> chi phi theo tung bot khong con")
                .contains("bot=\"nhan-su\"")
                .contains("bot=\"phap-che\"");

        // Chan nguyen nhan: khong duoc co gauge nao chiem ten Prometheus cua counter.
        assertThat(prom.find("rag.cost.usd").counter()).isNotNull();
        assertThat(prom.find("rag.cost.usd.total").gauge())
                .as("gauge nay chiem ten rag_cost_usd_total cua counter")
                .isNull();
    }

    @Test
    @DisplayName("Khong metric nao bi loai im lang khi xuat sang Prometheus")
    void noMeterIsSilentlyDroppedByPrometheus() {
        PrometheusMeterRegistry prom = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        RagMetrics all = new RagMetrics(prom);

        all.recordQuestion("nhan-su");
        all.recordAbstained("RERANK_SCORE_BELOW_THRESHOLD", "nhan-su");
        all.recordCacheHit("SEMANTIC", "nhan-su");
        all.recordError("nhan-su");
        all.recordUsage("OPENAI", "gpt-4o-mini", 10, 5, 0.002, "nhan-su");
        all.recordTotal(100, "nhan-su");
        all.recordRetrieval(30, "nhan-su");
        all.recordRerank(20, "nhan-su");
        all.recordGeneration(50, "nhan-su");

        // Every meter the class claims to publish has to actually be in the registry.
        for (String name : java.util.List.of("rag.questions", "rag.questions.abstained",
                "rag.abstain.reason", "rag.cache.hits", "rag.errors", "rag.cost.usd",
                "rag.tokens", "rag.tokens.input.total", "rag.tokens.output.total",
                "rag.latency")) {
            assertThat(prom.find(name).meters())
                    .as("metric '%s' khong co trong registry -> bi Prometheus loai", name)
                    .isNotEmpty();
        }
    }

    @Test
    @DisplayName("Do tre toan luot: p50 la trung binh co trong so, khong phai cua mot bot")
    void totalLatencyIsWeightedAcrossBots() {
        metrics.recordTotal(100, "nhan-su");
        metrics.recordTotal(100, "nhan-su");
        metrics.recordTotal(400, "phap-che");

        @SuppressWarnings("unchecked")
        Map<String, Object> latency = (Map<String, Object>) metrics.snapshot().get("latencyMs");

        assertThat(latency.get("totalP50")).isEqualTo(200L);
        assertThat(latency.get("totalP95")).isEqualTo(400L);
    }

    @Test
    @DisplayName("Moi chuoi rag.latency dung CHUNG mot bo khoa tag")
    // Prometheus drops series with mismatched tag keys without logging anything.
    void everyLatencySeriesHasTheSameTagKeys() {
        metrics.recordTotal(100, "nhan-su");
        metrics.recordRetrieval(30, "nhan-su");
        metrics.recordRerank(20, "nhan-su");
        metrics.recordGeneration(50, null);

        var keySets = registry.find("rag.latency").timers().stream()
                .map(t -> t.getId().getTags().stream()
                        .map(io.micrometer.core.instrument.Tag::getKey)
                        .sorted().toList())
                .distinct().toList();

        assertThat(registry.find("rag.latency").timers()).hasSize(4);
        assertThat(keySets).containsExactly(java.util.List.of("bot", "stage"));
    }

    @Test
    @DisplayName("Trung binh tung buoc cung cong don qua moi bot")
    void stageMeansAreAggregated() {
        metrics.recordRetrieval(100, "nhan-su");
        metrics.recordRetrieval(300, "phap-che");

        @SuppressWarnings("unchecked")
        Map<String, Object> latency = (Map<String, Object>) metrics.snapshot().get("latencyMs");

        assertThat(latency.get("retrievalMean")).isEqualTo(200L);
    }

    private double counter(String name, String bot) {
        Counter c = registry.find(name).tag("bot", bot).counter();
        return c == null ? -1 : c.count();
    }
}
