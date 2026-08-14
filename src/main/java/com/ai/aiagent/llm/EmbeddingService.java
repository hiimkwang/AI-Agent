package com.ai.aiagent.llm;

import com.ai.aiagent.config.RagProperties;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.AllMiniLmL6V2EmbeddingModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sinh vector nhung: CHIA LO, RETRY, va khong con phu thuoc mot nha cung cap duy nhat.
 *
 * Hai van de cua ban cu duoc sua o day:
 *
 *  1) {@code embedAll} goi MOT LAN cho toan bo chunk cua ca file. File lon vuot gioi
 *     han 2048 input / 300k token moi request cua OpenAI va lam hong CA luot nap, khong
 *     chi mot chunk. Gio chia lo theo {@code rag.ingestion.embed-batch-size} va retry
 *     co backoff khi bi rate-limit.
 *
 *  2) Embedding BAT BUOC qua OpenAI, ke ca khi chat bang Ollama - nghia la OpenAI la
 *     single point of failure cho ca he thong. Gio co 3 duong:
 *       OPENAI - chat luong tot nhat, can API key
 *       OLLAMA - chay local, bge-m3 rat tot voi tieng Viet
 *       LOCAL  - ONNX trong tien trinh, khong can API key va khong can mang
 *
 * Bat bien khong doi: vector cua CAU HOI va cua TAI LIEU phai cung mot model.
 */
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
        String provider = props.getEmbedding().getProvider();
        provider = provider == null ? "OPENAI" : provider.trim().toUpperCase();

        try {
            switch (provider) {
                case "LOCAL" -> {
                    // all-MiniLM-L6-v2: 384 chieu, chay ONNX trong tien trinh
                    this.model = new AllMiniLmL6V2EmbeddingModel();
                    this.activeProvider = "LOCAL";
                    warnIfDimensionMismatch(384);
                    log.warn("Embedding dang dung model LOCAL (all-MiniLM-L6-v2, 384 chieu). "
                            + "Khong can API key nhung CHAT LUONG TIENG VIET KEM - "
                            + "chi nen dung de thu nghiem pipeline.");
                }
                case "OLLAMA" -> {
                    this.model = OllamaEmbeddingModel.builder()
                            .baseUrl(props.getOllama().getBaseUrl())
                            .modelName(props.getEmbedding().getOllamaModel())
                            .timeout(Duration.ofMinutes(3))
                            .build();
                    this.activeProvider = "OLLAMA";
                    log.info("Embedding: Ollama {} tai {} ({} chieu)",
                            props.getEmbedding().getOllamaModel(),
                            props.getOllama().getBaseUrl(),
                            props.getEmbedding().getDimensions());
                }
                default -> {
                    String key = props.getOpenai().getApiKey();
                    if (key == null || key.isBlank()) {
                        log.error("""
                                ============================================================
                                THIEU OPENAI_API_KEY - embedding chua san sang, chuc nang nap
                                tai lieu va hoi dap se KHONG hoat dong.
                                Cach xu ly, chon mot trong ba:
                                  1) dat bien moi truong OPENAI_API_KEY
                                  2) rag.embedding.provider=OLLAMA (can Ollama chay local)
                                  3) rag.embedding.provider=LOCAL + rag.embedding.dimensions=384
                                     (khong can API key, chi dung de thu nghiem)
                                ============================================================""");
                        return;
                    }
                    OpenAiEmbeddingModel.OpenAiEmbeddingModelBuilder builder =
                            OpenAiEmbeddingModel.builder()
                                    .apiKey(key)
                                    .modelName(props.getEmbedding().getOpenaiModel())
                                    .dimensions(props.getEmbedding().getDimensions())
                                    .timeout(Duration.ofMinutes(2));
                    if (props.getOpenai().getBaseUrl() != null
                            && !props.getOpenai().getBaseUrl().isBlank()) {
                        builder.baseUrl(props.getOpenai().getBaseUrl());
                    }
                    this.model = builder.build();
                    this.activeProvider = "OPENAI";
                    log.info("Embedding: OpenAI {} ({} chieu), batch={}",
                            props.getEmbedding().getOpenaiModel(),
                            props.getEmbedding().getDimensions(),
                            props.getIngestion().getEmbedBatchSize());
                }
            }
        } catch (Exception e) {
            log.error("Khong khoi tao duoc model embedding ({}): {}", provider, e.getMessage());
        }
    }

    private void warnIfDimensionMismatch(int actual) {
        if (props.getEmbedding().getDimensions() != actual) {
            log.error("""
                    ============================================================
                    SO CHIEU KHONG KHOP: model '{}' sinh vector {} chieu nhung
                    rag.embedding.dimensions = {}.
                    Sua rag.embedding.dimensions thanh {} roi TAO LAI schema
                    (schema duoc sinh theo so chieu nay) va nap lai tai lieu.
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

    /** Nhung mot doan van (cau hoi, hoac khoa cho semantic cache). */
    public float[] embedOne(String text) {
        requireReady();
        return withRetry(() -> model.embed(text).content().vector(), 1);
    }

    /**
     * Nhung mot danh sach van ban, tu dong chia lo.
     *
     * @param onProgress goi sau moi lo voi so van ban da xong (co the null)
     */
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

    /**
     * Retry voi backoff luy tien. Rate-limit (429) va loi mang tam thoi la chuyen binh
     * thuong khi nap hang tram file - khong duoc de no lam chet ca job.
     */
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
                log.warn("Embedding loi ({}), thu lai lan {}/{} sau {}ms",
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
