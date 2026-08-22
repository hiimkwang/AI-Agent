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

@Component
public class RagMetrics {

    public static final String WEB = "web";

    private final MeterRegistry registry;

    private final Counter documentsIngested;
    private final Counter chunksIndexed;

    private final AtomicLong inputTokens = new AtomicLong();
    private final AtomicLong outputTokens = new AtomicLong();
    private final DoubleAdder costUsd = new DoubleAdder();

    public RagMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.documentsIngested = Counter.builder("rag.ingest.documents").register(registry);
        this.chunksIndexed = Counter.builder("rag.ingest.chunks").register(registry);

        registry.gauge("rag.tokens.input.total", inputTokens, AtomicLong::doubleValue);
        registry.gauge("rag.tokens.output.total", outputTokens, AtomicLong::doubleValue);
        // No gauge for cost: Micrometer exports a counter with a "_total" suffix, so a gauge
        // named rag.cost.usd.total would claim the same Prometheus name as the rag.cost.usd
        // counter below. Prometheus then rejects the counter and the per-bot cost series
        // disappears from /actuator/prometheus with only a warning. Use sum(rag_cost_usd_total)
        // for the grand total; snapshot() still reports it from costUsd directly.
    }

    public void recordQuestion(String bot) {
        counter("rag.questions", "bot", label(bot)).increment();
    }

    public void recordAbstained(String reason, String bot) {
        counter("rag.questions.abstained", "bot", label(bot)).increment();
        Counter.builder("rag.abstain.reason")
                .tag("reason", reason == null ? "unknown" : reason)
                .tag("bot", label(bot))
                .register(registry).increment();
    }

    public void recordCacheHit(String kind, String bot) {
        Counter.builder("rag.cache.hits")
                .tag("kind", "SEMANTIC".equalsIgnoreCase(kind) ? "semantic" : "exact")
                .tag("bot", label(bot))
                .register(registry).increment();
    }

    public void recordError(String bot) {
        counter("rag.errors", "bot", label(bot)).increment();
    }

    public void recordInvalidCitation(String bot) {
        counter("rag.citations.invalid", "bot", label(bot)).increment();
    }

    public void recordIngest(int chunks) {
        documentsIngested.increment();
        chunksIndexed.increment(chunks);
    }

    public void recordTotal(long ms, String bot) {
        stage("total", bot).record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordRetrieval(long ms, String bot) {
        stage("retrieval", bot).record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordRerank(long ms, String bot) {
        stage("rerank", bot).record(ms, TimeUnit.MILLISECONDS);
    }

    public void recordGeneration(long ms, String bot) {
        stage("generation", bot).record(ms, TimeUnit.MILLISECONDS);
    }

    // Every series of a metric must carry the same tag keys. Prometheus drops
    // mismatched series silently, so both stage and bot are always tagged.
    private Timer stage(String stage, String bot) {
        return Timer.builder("rag.latency")
                .tag("stage", stage)
                .tag("bot", label(bot))
                .register(registry);
    }

    public void recordUsage(String provider, String model, int in, int out, double cost, String bot) {
        inputTokens.addAndGet(in);
        outputTokens.addAndGet(out);
        costUsd.add(cost);
        tokens(provider, model, bot, "input").increment(in);
        tokens(provider, model, bot, "output").increment(out);
        Counter.builder("rag.cost.usd").tag("bot", label(bot))
                .register(registry).increment(cost);
    }

    public Map<String, Object> snapshot() {
        double questions = sum("rag.questions");
        double abstained = sum("rag.questions.abstained");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("questions", (long) questions);
        out.put("abstained", (long) abstained);
        out.put("abstainRate", questions == 0 ? null
                : Math.round(abstained * 1000.0 / questions) / 10.0);
        out.put("cacheHitsExact", (long) sumTagged("rag.cache.hits", "kind", "exact"));
        out.put("cacheHitsSemantic", (long) sumTagged("rag.cache.hits", "kind", "semantic"));
        out.put("errors", (long) sum("rag.errors"));
        out.put("documentsIngested", (long) documentsIngested.count());
        out.put("chunksIndexed", (long) chunksIndexed.count());
        out.put("inputTokens", inputTokens.get());
        out.put("outputTokens", outputTokens.get());
        out.put("costUsd", Math.round(costUsd.doubleValue() * 1_000_000.0) / 1_000_000.0);
        out.put("latencyMs", Map.of(
                "totalP50", stageMean("total"),
                "totalP95", stageMax("total"),
                "retrievalMean", stageMean("retrieval"),
                "rerankMean", stageMean("rerank"),
                "generationMean", stageMean("generation")));
        return out;
    }

    private Counter counter(String name, String tag, String value) {
        return Counter.builder(name).tag(tag, value).register(registry);
    }

    private Counter tokens(String provider, String model, String bot, String direction) {
        return Counter.builder("rag.tokens")
                .tag("provider", provider == null ? "?" : provider)
                .tag("model", model == null ? "?" : model)
                .tag("bot", label(bot))
                .tag("direction", direction)
                .register(registry);
    }

    private static String label(String bot) {
        return bot == null || bot.isBlank() ? WEB : bot;
    }

    private double sum(String name) {
        double total = 0;
        for (Counter c : registry.find(name).counters()) total += c.count();
        return total;
    }

    private double sumTagged(String name, String tag, String value) {
        double total = 0;
        for (Counter c : registry.find(name).tag(tag, value).counters()) total += c.count();
        return total;
    }

    private long stageMean(String stage) {
        double weighted = 0;
        long count = 0;
        for (Timer t : registry.find("rag.latency").tag("stage", stage).timers()) {
            weighted += t.mean(TimeUnit.MILLISECONDS) * t.count();
            count += t.count();
        }
        return count == 0 ? 0 : Math.round(weighted / count);
    }

    private long stageMax(String stage) {
        long max = 0;
        for (Timer t : registry.find("rag.latency").tag("stage", stage).timers()) {
            max = Math.max(max, Math.round(t.max(TimeUnit.MILLISECONDS)));
        }
        return max;
    }
}
