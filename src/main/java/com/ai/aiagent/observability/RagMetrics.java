package com.ai.aiagent.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Do luong: so cau hoi, do tre tung buoc, token, chi phi, ty le tu choi, cache hit.
 *
 * Truoc day khong do gi ca - khong biet buoc nao cham thi khong toi uu duoc buoc nao,
 * va khong biet cau hoi nao dat. Toan bo so lieu vao Micrometer nen lay duoc qua
 * {@code /actuator/prometheus}, va cung duoc tong hop cho trang quan tri.
 */
@Component
public class RagMetrics {

    private final MeterRegistry registry;

    private final Counter questions;
    private final Counter abstained;
    private final Counter cacheHitsExact;
    private final Counter cacheHitsSemantic;
    private final Counter errors;
    private final Counter documentsIngested;
    private final Counter chunksIndexed;

    private final Timer totalLatency;
    private final Timer retrievalLatency;
    private final Timer rerankLatency;
    private final Timer generationLatency;

    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final DoubleAdder costUsd = new DoubleAdder();

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.questions = Counter.builder("rag.questions").register(registry);
        this.abstained = Counter.builder("rag.questions.abstained")
                .description("So cau tra loi 'khong tim thay' (tu choi tra loi)").register(registry);
        this.cacheHitsExact = Counter.builder("rag.cache.hits").tag("kind", "exact").register(registry);
        this.cacheHitsSemantic = Counter.builder("rag.cache.hits").tag("kind", "semantic").register(registry);
        this.errors = Counter.builder("rag.errors").register(registry);
        this.documentsIngested = Counter.builder("rag.ingest.documents").register(registry);
        this.chunksIndexed = Counter.builder("rag.ingest.chunks").register(registry);

        this.totalLatency = Timer.builder("rag.latency").tag("stage", "total").register(registry);
        this.retrievalLatency = Timer.builder("rag.latency").tag("stage", "retrieval").register(registry);
        this.rerankLatency = Timer.builder("rag.latency").tag("stage", "rerank").register(registry);
        this.generationLatency = Timer.builder("rag.latency").tag("stage", "generation").register(registry);

        registry.gauge("rag.tokens.input.total", inputTokens, AtomicLong::doubleValue);
        registry.gauge("rag.tokens.output.total", outputTokens, AtomicLong::doubleValue);
        registry.gauge("rag.cost.usd.total", costUsd, DoubleAdder::doubleValue);
    }

    public void recordQuestion() {
        questions.increment();
    }

    public void recordAbstained(String reason) {
        abstained.increment();
        Counter.builder("rag.abstain.reason")
                .tag("reason", reason == null ? "unknown" : reason)
                .register(registry).increment();
    }

    public void recordCacheHit(String kind) {
        if ("SEMANTIC".equalsIgnoreCase(kind)) cacheHitsSemantic.increment();
        else cacheHitsExact.increment();
    }

    public void recordError() {
        errors.increment();
    }

    public void recordIngest(int chunks) {
        documentsIngested.increment();
        chunksIndexed.increment(chunks);
    }

    public void recordTotal(long ms) {
        totalLatency.record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordRetrieval(long ms) {
        retrievalLatency.record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordRerank(long ms) {
        rerankLatency.record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordGeneration(long ms) {
        generationLatency.record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordUsage(String provider, String model, int in, int out, double cost) {
        inputTokens.addAndGet(in);
        outputTokens.addAndGet(out);
        costUsd.add(cost);
        Counter.builder("rag.tokens")
                .tag("provider", provider == null ? "?" : provider)
                .tag("model", model == null ? "?" : model)
                .tag("direction", "input")
                .register(registry).increment(in);
        Counter.builder("rag.tokens")
                .tag("provider", provider == null ? "?" : provider)
                .tag("model", model == null ? "?" : model)
                .tag("direction", "output")
                .register(registry).increment(out);
    }

    /** Tom tat de hien tren trang quan tri (khong can Prometheus). */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", (long) questions.count());
        out.put("abstained", (long) abstained.count());
        out.put("abstainRate", questions.count() == 0 ? null
                : Math.round(abstained.count() * 1000.0 / questions.count()) / 10.0);
        out.put("cacheHitsExact", (long) cacheHitsExact.count());
        out.put("cacheHitsSemantic", (long) cacheHitsSemantic.count());
        out.put("errors", (long) errors.count());
        out.put("documentsIngested", (long) documentsIngested.count());
        out.put("chunksIndexed", (long) chunksIndexed.count());
        out.put("inputTokens", inputTokens.get());
        out.put("outputTokens", outputTokens.get());
        out.put("costUsd", Math.round(costUsd.doubleValue() * 1_000_000.0) / 1_000_000.0);
        out.put("latencyMs", Map.of(
                "totalP50", percentile(totalLatency, 0.5),
                "totalP95", percentile(totalLatency, 0.95),
                "retrievalMean", mean(retrievalLatency),
                "rerankMean", mean(rerankLatency),
                "generationMean", mean(generationLatency)));
        return out;
    }

    private long mean(Timer timer) {
        return Math.round(timer.mean(TimeUnit.MILLISECONDS));
    }

    private long percentile(Timer timer, double p) {
        // Timer mac dinh khong luu histogram; dung max/mean lam xap xi thuc dung
        return p >= 0.95 ? Math.round(timer.max(TimeUnit.MILLISECONDS)) : mean(timer);
    }
}
