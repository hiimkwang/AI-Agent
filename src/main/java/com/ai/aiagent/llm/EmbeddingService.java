package com.ai.aiagent.llm;

import com.ai.aiagent.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
@Slf4j
public class EmbeddingService {

    private final RagProperties props;
    private EmbeddingModel model;
    private String activeProvider = "NONE";
    private final AtomicLong embeddedTexts = new AtomicLong();
    private final AtomicLong embedCalls = new AtomicLong();

    public EmbeddingService(RagProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        RagProperties.Embedding cfg = props.getEmbedding();
        String provider = cfg.getProvider() == null
                ? "OPENAI" : cfg.getProvider().trim().toUpperCase();

        EmbeddingModelFactory.Spec spec = new EmbeddingModelFactory.Spec(
                provider, cfg.modelName(), cfg.getDimensions());
        this.model = EmbeddingModelFactory.build(spec, props, "chinh");

        if (this.model == null) {
            if ("OPENAI".equals(provider)) {
                log.error("""
                        ============================================================
                        OPENAI_API_KEY is missing. Embedding is not ready, so document
                        ingest and question answering will NOT work.
                        Pick one of:
                          1) set the OPENAI_API_KEY environment variable
                          2) rag.embedding.provider=OLLAMA (needs Ollama running locally)
                          3) rag.embedding.provider=LOCAL + rag.embedding.dimensions=384
                             (no API key, suitable for smoke tests only)
                        ============================================================""");
            }
            return;
        }

        this.activeProvider = provider;
        if ("LOCAL".equals(provider)) {
            warnIfDimensionMismatch(384);
            log.warn("Embedding is using the LOCAL model (all-MiniLM-L6-v2, 384 dims). No API key "
                    + "needed, but Vietnamese quality is POOR - smoke tests only.");
        }
    }

    private void warnIfDimensionMismatch(int actual) {
        if (props.getEmbedding().getDimensions() != actual) {
            log.error("""
                    ============================================================
                    DIMENSION MISMATCH: model '{}' produces {}-dimension vectors but
                    rag.embedding.dimensions = {}.
                    Set rag.embedding.dimensions to {}, then RECREATE the schema
                    (the DDL is generated from this number) and re-ingest everything.
                    ============================================================""",
                    props.getEmbedding().modelName(), actual,
                    props.getEmbedding().getDimensions(), actual);
        }
    }

    public boolean isReady() {
        return model != null;
    }

    public int dimensions() {
        return props.getEmbedding().getDimensions();
    }

    public String modelName() {
        return props.getEmbedding().modelName();
    }

    public String activeProvider() {
        return activeProvider;
    }

    public float[] embedOne(String text) {
        requireReady();
        return withRetry(() -> model.embed(text).content().vector(), 1);
    }

    public List<float[]> embedAll(List<String> texts, java.util.function.IntConsumer onProgress) {
        requireReady();
        int batchSize = Math.max(1, props.getIngestion().getEmbedBatchSize());
        List<float[]> out = new ArrayList<>(texts.size());

        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(texts.size(), from + batchSize);
            List<TextSegment> segments = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                segments.add(TextSegment.from(texts.get(i)));
            }
            final int size = segments.size();
            List<Embedding> embeddings = withRetry(() -> model.embedAll(segments).content(), size);
            for (Embedding e : embeddings) {
                out.add(e.vector());
            }
            if (onProgress != null) onProgress.accept(out.size());
        }
        return out;
    }

    public List<float[]> embedAll(List<String> texts) {
        return embedAll(texts, null);
    }

    public long totalEmbeddedTexts() {
        return embeddedTexts.get();
    }

    public long totalEmbedCalls() {
        return embedCalls.get();
    }

    private void requireReady() {
        if (model == null) {
            throw new IllegalStateException("Embedding chua san sang: kiem tra "
                    + "rag.embedding.provider va API key tuong ung.");
        }
    }

    private <T> T withRetry(java.util.function.Supplier<T> action, int count) {
        int maxRetries = Math.max(0, props.getIngestion().getEmbedMaxRetries());
        long base = Math.max(100, props.getIngestion().getEmbedRetryBaseDelayMs());
        RuntimeException last = null;

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                T result = action.get();
                embedCalls.incrementAndGet();
                embeddedTexts.addAndGet(count);
                return result;
            } catch (RuntimeException e) {
                last = e;
                if (attempt == maxRetries) break;
                long delay = base * (1L << attempt);
                log.warn("Embedding call failed ({}), retry {}/{} in {}ms",
                        e.getMessage(), attempt + 1, maxRetries, delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Bi ngat khi cho retry embedding.", ie);
                }
            }
        }
        throw new IllegalStateException(
                "Embedding that bai sau " + (maxRetries + 1) + " lan thu: "
                        + (last == null ? "khong ro" : last.getMessage()), last);
    }
}
