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
 *
 * <h2>The {@code bot}</h2>
 * Moi so do cua mot luot hoi-dap deu mang the {@code bot}. Ly do: voi nen tang nhieu
 * bot, so lieu GOP khong con dung de chan doan - ty le tu choi toan he 8% co the la
 * moi bot deu 8%, hoac la bot Phap che 40% con lai binh thuong. Truong hop thu hai
 * co nghia la collection cua bot do thieu tai lieu, va so lieu gop giau mat dieu do.
 *
 * The luon co gia tri ({@code "web"} cho duong khong qua bot) chu khong bao gio rong:
 * cung mot ten metric ma luc co luc khong co the se lam hong chuoi so lieu Prometheus.
 * So bot la huu han va nho nen khong co nguy co bung no so chuoi (cardinality).
 */
@Component
public class RagMetrics {

    /** Duong web/goi noi bo: khong thuoc bot nao. Xem {@code BotProfile.label()}. */
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
        registry.gauge("rag.cost.usd.total", costUsd, DoubleAdder::doubleValue);
    }

    public void recordQuestion(String bot) {
        counter("rag.questions", "bot", label(bot)).increment();
    }

    /**
     * @param reason ly do tu choi ({@code RelevanceGate.Decision.reason()})
     * @param bot    bot tra loi; ty le tu choi tang vot o MOT bot la dau hieu collection
     *               cua bot do thieu tai lieu, khong phai loi cua bo truy xuat
     */
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

    /**
     * So cau tra loi dan nguon KHONG ton tai (da bi bo moc).
     *
     * Ty le nay tang la dau hieu som prompt hoac model dang xuong cap - de nhan ra hon
     * nhieu so voi viec doc tung cau tra loi. Gan the bot vi persona rieng cua tung bot
     * la mot trong nhung thu de lam hong cach dan nguon nhat.
     */
    public void recordInvalidCitation(String bot) {
        counter("rag.citations.invalid", "bot", label(bot)).increment();
    }

    public void recordIngest(int chunks) {
        documentsIngested.increment();
        chunksIndexed.increment(chunks);
    }

    /** Do tre TOAN LUOT, gan the bot: mot bot dung model cham keo p95 rieng no len. */
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

    /**
     * MOI chuoi {@code rag.latency} phai co DU CA HAI the {@code stage} va {@code bot}.
     *
     * Day khong phai lua chon phong cach. Prometheus doi moi chuoi cung mot ten metric
     * phai co cung bo KHOA tag; de {@code stage=total} mang them the {@code bot} con ba
     * buoc kia thi khong, va chuoi total bi LOAI BO IM LANG khoi
     * {@code /actuator/prometheus} - da xay ra that, phat hien khi doi chieu endpoint.
     * Khong co canh bao nao o muc log mac dinh.
     */
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

    /** Tom tat de hien tren trang quan tri (khong can Prometheus). */
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

    // ============================================================ Noi bo

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

    /** Cong don moi chuoi cua mot metric - so gop van la so gop du da tach the bot. */
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

    /**
     * Trung binh mot buoc, GOP MOI BOT - trung binh co trong so theo so luot.
     *
     * Khong lay trung binh cong cua cac trung binh: mot bot tra loi 3 cau se keo con so
     * toan he lech han.
     *
     * Timer mac dinh khong luu histogram nen o day khong co percentile that; con so
     * CHINH XAC lay tu DB qua bao cao theo bot ({@code UsageReportRepository}) - o do co
     * {@code percentile_cont} that su.
     */
    private long stageMean(String stage) {
        double weighted = 0;
        long count = 0;
        for (Timer t : registry.find("rag.latency").tag("stage", stage).timers()) {
            weighted += t.mean(TimeUnit.MILLISECONDS) * t.count();
            count += t.count();
        }
        return count == 0 ? 0 : Math.round(weighted / count);
    }

    /** Xap xi p95 thuc dung: gia tri lon nhat quan sat duoc tren moi bot. */
    private long stageMax(String stage) {
        long max = 0;
        for (Timer t : registry.find("rag.latency").tag("stage", stage).timers()) {
            max = Math.max(max, Math.round(t.max(TimeUnit.MILLISECONDS)));
        }
        return max;
    }
}
