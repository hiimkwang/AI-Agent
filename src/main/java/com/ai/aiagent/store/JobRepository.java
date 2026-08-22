package com.ai.aiagent.store;

import com.ai.aiagent.store.StoreModels.JobStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Repository
@Slf4j
public class JobRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public JobRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void create(String id, String kind, String category, int total, String createdBy) {
        jdbc.update("""
                INSERT INTO rag_ingest_jobs (id, state, kind, category, total, created_by, errors)
                VALUES (?, 'RUNNING', ?, ?, ?, ?, '[]'::jsonb)
                """, id, kind, category, total, createdBy);
    }

    public void progress(String id, String currentFile, int processed, int succeeded,
                         int failed, int skipped, int totalChunks) {
        jdbc.update("""
                UPDATE rag_ingest_jobs
                   SET current_file = ?, processed = ?, succeeded = ?, failed = ?,
                       skipped = ?, total_chunks = ?
                 WHERE id = ?
                """, currentFile, processed, succeeded, failed, skipped, totalChunks, id);
    }

    public void addError(String id, String message) {
        String trimmed = message == null ? "" : (message.length() > 500
                ? message.substring(0, 500) : message);
        try {
            String json = mapper.writeValueAsString(List.of(trimmed));
            jdbc.update("""
                    UPDATE rag_ingest_jobs
                       SET errors = CASE
                             WHEN jsonb_array_length(coalesce(errors, '[]'::jsonb)) >= 200 THEN errors
                             ELSE coalesce(errors, '[]'::jsonb) || ?::jsonb
                           END
                     WHERE id = ?
                    """, json, id);
        } catch (Exception e) {
            log.warn("Could not append the error to job {}: {}", id, e.getMessage());
        }
    }

    public void finish(String id, String state) {
        jdbc.update("UPDATE rag_ingest_jobs SET state = ?, current_file = NULL, finished_at = now() "
                + "WHERE id = ?", state, id);
    }

    public void requestCancel(String id) {
        jdbc.update("UPDATE rag_ingest_jobs SET cancel_requested = true WHERE id = ? AND state = 'RUNNING'",
                id);
    }

    public boolean isCancelRequested(String id) {
        Boolean flag = jdbc.queryForObject(
                "SELECT cancel_requested FROM rag_ingest_jobs WHERE id = ?", Boolean.class, id);
        return Boolean.TRUE.equals(flag);
    }

    public Optional<JobStatus> find(String id) {
        List<JobStatus> found = jdbc.query(
                "SELECT * FROM rag_ingest_jobs WHERE id = ?", ROW_MAPPER, id);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    public List<JobStatus> recent(int limit) {
        return jdbc.query("SELECT * FROM rag_ingest_jobs ORDER BY started_at DESC LIMIT ?",
                ROW_MAPPER, Math.min(Math.max(limit, 1), 100));
    }

    public int markOrphanedAsInterrupted() {
        return jdbc.update("""
                UPDATE rag_ingest_jobs
                   SET state = 'INTERRUPTED', finished_at = now()
                 WHERE state = 'RUNNING'
                """);
    }

    public int purgeFinishedOlderThanDays(int days) {
        return jdbc.update("""
                DELETE FROM rag_ingest_jobs
                 WHERE state <> 'RUNNING'
                   AND COALESCE(finished_at, started_at) < now() - (? || ' days')::interval
                """, String.valueOf(Math.max(1, days)));
    }

    private static final RowMapper<JobStatus> ROW_MAPPER = (rs, n) -> {
        List<String> errors = new ArrayList<>();
        String raw = rs.getString("errors");
        if (raw != null && !raw.isBlank() && !"[]".equals(raw)) {
            try {
                com.fasterxml.jackson.databind.JsonNode node =
                        new ObjectMapper().readTree(raw);
                node.forEach(e -> errors.add(e.asText()));
            } catch (Exception ignored) {
                errors.add(raw);
            }
        }
        Timestamp started = rs.getTimestamp("started_at");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new JobStatus(
                rs.getString("id"),
                rs.getString("state"),
                rs.getString("kind"),
                rs.getString("category"),
                rs.getInt("total"),
                rs.getInt("processed"),
                rs.getInt("succeeded"),
                rs.getInt("failed"),
                rs.getInt("skipped"),
                rs.getInt("total_chunks"),
                rs.getString("current_file"),
                errors,
                rs.getBoolean("cancel_requested"),
                rs.getString("created_by"),
                started == null ? java.time.Instant.now() : started.toInstant(),
                finished == null ? null : finished.toInstant());
    };
}
