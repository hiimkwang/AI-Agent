package com.ai.aiagent.eval;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.ingest.MarkdownChunker;
import com.ai.aiagent.llm.EmbeddingModelFactory;
import com.ai.aiagent.llm.EmbeddingService;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.store.ChunkRepository;
import com.ai.aiagent.store.EvalRepository;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import com.ai.aiagent.store.Vectors;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class EmbeddingTrialService {

    public record BuildStatus(String state, int total, int done, int failed, String message,
                              String model, int dimensions) {
        static BuildStatus idle() {
            return new BuildStatus("IDLE", 0, 0, 0, null, null, 0);
        }
    }

    public record IndexScore(String label, String model, int dimensions,
                             Map<String, Double> recallAt, Double mrr, int measured) {
    }

    public record Comparison(String suite, int total, int measured,
                             IndexScore current, IndexScore trial, String verdict) {
    }

    private static final int[] CUTOFFS = {1, 3, 5, 10};

    private static final int HNSW_MAX_DIMENSIONS = 2000;

    private final RagProperties props;
    private final JdbcTemplate jdbc;
    private final ChunkRepository chunks;
    private final EvalRepository evalRepository;
    private final EmbeddingService primary;
    private final AtomicReference<BuildStatus> status = new AtomicReference<>(BuildStatus.idle());
    private volatile EmbeddingModel trialModel;

    public EmbeddingTrialService(RagProperties props, JdbcTemplate jdbc, ChunkRepository chunks,
                                 EvalRepository evalRepository, EmbeddingService primary) {
        this.props = props;
        this.jdbc = jdbc;
        this.chunks = chunks;
        this.evalRepository = evalRepository;
        this.primary = primary;
    }

    public BuildStatus status() {
        return status.get();
    }

    public Map<String, Object> describe() {
        RagProperties.Trial cfg = props.getEmbedding().getTrial();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", cfg.isEnabled());
        out.put("current", Map.of(
                "provider", primary.activeProvider(),
                "model", primary.modelName(),
                "dimensions", primary.dimensions()));
        out.put("trial", Map.of(
                "provider", cfg.getProvider(),
                "model", cfg.modelName(),
                "dimensions", cfg.getDimensions(),
                "table", cfg.getTable()));
        out.put("build", status.get());
        out.put("chunksInIndex", countChunks());
        out.put("chunksEmbedded", countTrial());
        return out;
    }

    public synchronized BuildStatus startBuild(boolean rebuild) {
        RagProperties.Trial cfg = props.getEmbedding().getTrial();
        if (!cfg.isEnabled()) {
            throw new IllegalArgumentException("Chua bat rag.embedding.trial.enabled=true.");
        }
        if ("RUNNING".equals(status.get().state())) {
            return status.get();
        }
        ChunkRepository.requireValidTableName(cfg.getTable());

        EmbeddingModelFactory.Spec spec = new EmbeddingModelFactory.Spec(
                cfg.getProvider(), cfg.modelName(), cfg.getDimensions());
        EmbeddingModel model = EmbeddingModelFactory.build(spec, props, "thu nghiem");
        if (model == null) {
            throw new IllegalArgumentException("Khong khoi tao duoc model embedding ung vien. "
                    + "Kiem tra rag.embedding.trial.provider va API key tuong ung.");
        }
        this.trialModel = model;

        int dimensions = spec.actualDimensions();
        prepareTable(cfg.getTable(), dimensions, rebuild);

        int total = countChunks();
        status.set(new BuildStatus("RUNNING", total, 0, 0, null, cfg.modelName(), dimensions));

        Thread worker = new Thread(() -> build(cfg, model, total), "embedding-trial");
        worker.setDaemon(true);
        worker.start();
        return status.get();
    }

    private void prepareTable(String table, int dimensions, boolean rebuild) {
        if (rebuild) {
            jdbc.execute("DROP TABLE IF EXISTS " + table);
        }
        jdbc.execute("""
                CREATE TABLE IF NOT EXISTS %s (
                    chunk_id  BIGINT PRIMARY KEY REFERENCES rag_chunks (id) ON DELETE CASCADE,
                    embedding vector(%d) NOT NULL
                )""".formatted(table, dimensions));

        if (dimensions > HNSW_MAX_DIMENSIONS) {
            log.warn("Trial table has {} dimensions, above the HNSW limit of {}. Skipping the "
                            + "index and scanning the whole table: more accurate, slower.",
                    dimensions, HNSW_MAX_DIMENSIONS);
            return;
        }
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_" + table + "_embedding ON " + table
                + " USING hnsw (embedding vector_cosine_ops)");
    }

    private void build(RagProperties.Trial cfg, EmbeddingModel model, int total) {
        int done = 0;
        int failed = 0;
        long lastId = 0;
        int batch = Math.max(8, cfg.getBatchSize());

        try {
            while (true) {
                List<Row> rows = nextPage(lastId, batch);
                if (rows.isEmpty()) break;
                lastId = rows.get(rows.size() - 1).id();

                try {
                    List<TextSegment> segments = rows.stream()
                            .map(r -> TextSegment.from(r.embedText()))
                            .toList();
                    List<Embedding> vectors = model.embedAll(segments).content();
                    insert(cfg.getTable(), rows, vectors);
                    done += rows.size();
                } catch (Exception e) {
                    failed += rows.size();
                    log.warn("Trial embedding batch failed (up to id={}): {}", lastId, e.getMessage());
                }
                status.set(new BuildStatus("RUNNING", total, done, failed, null,
                        cfg.modelName(), cfg.getDimensions()));
            }
            status.set(new BuildStatus("DONE", total, done, failed,
                    failed == 0 ? "Đã nhúng xong toàn bộ." : "Xong, nhưng " + failed + " chunk lỗi.",
                    cfg.modelName(), cfg.getDimensions()));
            log.info("Trial embedding table built: {} chunks, {} failures.", done, failed);
        } catch (Exception e) {
            log.error("Building the trial embedding table failed", e);
            status.set(new BuildStatus("FAILED", total, done, failed,
                    "Lỗi: " + e.getMessage(), cfg.modelName(), cfg.getDimensions()));
        }
    }

    private record Row(long id, String embedText) {
    }

    private List<Row> nextPage(long afterId, int limit) {
        boolean withIdentity = props.getChunking().isPrefixDocumentIdentity();
        return jdbc.query("""
                SELECT c.id, c.heading_path, c.context, c.content,
                       d.title, d.doc_number, d.effective_date
                  FROM rag_chunks c
                  LEFT JOIN rag_documents d ON d.id = c.document_id
                 WHERE c.id > ?
                 ORDER BY c.id
                 LIMIT ?
                """, (rs, i) -> {
            StringBuilder sb = new StringBuilder();
            if (withIdentity) {
                java.sql.Date effective = rs.getDate("effective_date");
                String identity = MarkdownChunker.documentIdentity(
                        rs.getString("title"), rs.getString("doc_number"),
                        effective == null ? null : effective.toLocalDate());
                if (!identity.isBlank()) sb.append(identity).append('\n');
            }
            append(sb, rs.getString("heading_path"));
            append(sb, rs.getString("context"));
            sb.append(rs.getString("content") == null ? "" : rs.getString("content"));
            return new Row(rs.getLong("id"), sb.toString());
        }, afterId, limit);
    }

    private static void append(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) sb.append(value).append('\n');
    }

    private void insert(String table, List<Row> rows, List<Embedding> vectors) {
        if (vectors.size() != rows.size()) {
            throw new IllegalStateException("So vector khong khop so chunk trong lo.");
        }
        List<Object[]> args = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            args.add(new Object[]{rows.get(i).id(), Vectors.toLiteral(vectors.get(i).vector())});
        }
        jdbc.batchUpdate("INSERT INTO " + table + " (chunk_id, embedding) VALUES (?, ?::vector) "
                + "ON CONFLICT (chunk_id) DO UPDATE SET embedding = EXCLUDED.embedding", args);
    }

    public Comparison compare(String suite, AccessScope scope, int topK) {
        RagProperties.Trial cfg = props.getEmbedding().getTrial();
        if (countTrial() == 0) {
            throw new IllegalArgumentException("Bang thu nghiem con rong. "
                    + "Goi POST /api/v1/rag/admin/embedding-trial/build truoc.");
        }
        if (trialModel == null) {
            EmbeddingModelFactory.Spec spec = new EmbeddingModelFactory.Spec(
                    cfg.getProvider(), cfg.modelName(), cfg.getDimensions());
            trialModel = EmbeddingModelFactory.build(spec, props, "thu nghiem");
        }
        if (trialModel == null) {
            throw new IllegalArgumentException("Khong khoi tao duoc model embedding ung vien. "
                    + "Kiem tra rag.embedding.trial.provider va API key tuong ung.");
        }

        List<EvalRepository.EvalCase> cases = evalRepository.listCases(suite, true).stream()
                .filter(c -> c.expectedSource() != null && !c.expectedSource().isBlank())
                .toList();
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("Bo '" + suite
                    + "' khong co case nao khai bao expectedSource - khong do duoc truy xuat.");
        }

        ChunkRepository.SearchFilter filter = new ChunkRepository.SearchFilter(
                scope.narrowTo(null), scope.isAdmin() ? Set.of() : scope.roles(),
                props.getRetrieval().isExcludeExpired());

        int[] currentHits = new int[CUTOFFS.length];
        int[] trialHits = new int[CUTOFFS.length];
        double currentMrr = 0;
        double trialMrr = 0;

        for (EvalRepository.EvalCase testCase : cases) {
            String expected = testCase.expectedSource().toLowerCase(Locale.ROOT);

            Integer rankCurrent = rankOf(
                    chunks.vectorSearch(primary.embedOne(testCase.question()), topK, filter),
                    expected);
            Integer rankTrial = rankOf(
                    chunks.trialVectorSearch(cfg.getTable(),
                            trialModel.embed(testCase.question()).content().vector(), topK, filter),
                    expected);

            currentMrr += score(rankCurrent, currentHits);
            trialMrr += score(rankTrial, trialHits);
        }

        int n = cases.size();
        IndexScore current = new IndexScore("current", primary.modelName(), primary.dimensions(),
                recallMap(currentHits, n), round(currentMrr / n), n);
        IndexScore trial = new IndexScore("trial", cfg.modelName(), cfg.getDimensions(),
                recallMap(trialHits, n), round(trialMrr / n), n);

        log.info("Embedding comparison on '{}': {} ({} dims) MRR={} | {} ({} dims) MRR={}",
                suite, current.model(), current.dimensions(), current.mrr(),
                trial.model(), trial.dimensions(), trial.mrr());

        return new Comparison(suite, n, n, current, trial, verdict(current, trial, n));
    }

    private String verdict(IndexScore current, IndexScore trial, int n) {
        double delta = (trial.mrr() == null ? 0 : trial.mrr())
                - (current.mrr() == null ? 0 : current.mrr());
        String size = n < 30
                ? " CANH BAO: bo cau hoi chi co " + n + " case - qua nho de ket luan. "
                  + "Nen co it nhat 50-100 cau hoi that truoc khi quyet dinh nap lai toan bo kho."
                : "";
        if (Math.abs(delta) < 0.02) {
            return "Hai model tuong duong (chenh MRR " + round(delta) + ")." + size;
        }
        return (delta > 0
                ? "Model ung vien TOT HON (MRR +" + round(delta) + ")."
                : "Model ung vien KEM HON (MRR " + round(delta) + ") - khong nen doi.") + size;
    }

    private double score(Integer rank, int[] hits) {
        if (rank == null) return 0;
        for (int i = 0; i < CUTOFFS.length; i++) {
            if (rank <= CUTOFFS[i]) hits[i]++;
        }
        return 1.0 / rank;
    }

    private Map<String, Double> recallMap(int[] hits, int n) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i = 0; i < CUTOFFS.length; i++) {
            out.put("@" + CUTOFFS[i], round((double) hits[i] / n));
        }
        return out;
    }

    private Integer rankOf(List<RetrievedChunk> results, String expectedLower) {
        for (int i = 0; i < results.size(); i++) {
            String name = results.get(i).getFileName();
            if (name != null && name.toLowerCase(Locale.ROOT).contains(expectedLower)) {
                return i + 1;
            }
        }
        return null;
    }

    private int countChunks() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM rag_chunks", Integer.class);
        return n == null ? 0 : n;
    }

    private int countTrial() {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT count(*) FROM " + props.getEmbedding().getTrial().getTable(),
                    Integer.class);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    public Map<String, Object> discard() {
        String table = props.getEmbedding().getTrial().getTable();
        ChunkRepository.requireValidTableName(table);
        jdbc.execute("DROP TABLE IF EXISTS " + table);
        status.set(BuildStatus.idle());
        return Map.of("message", "Đã xoá bảng thử nghiệm '" + table + "'.");
    }

    private static Double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
