package com.ai.aiagent.eval;

import com.ai.aiagent.llm.InternalLlm;
import com.ai.aiagent.store.EvalRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

@Service
@Slf4j
public class EvalCaseBuilder {

    public record BuildStatus(String state, int target, int done, int skipped, String message) {
        static BuildStatus idle() {
            return new BuildStatus("IDLE", 0, 0, 0, null);
        }
    }

    private final JdbcTemplate jdbc;
    private final EvalRepository repository;
    private final InternalLlm internalLlm;
    private final AtomicReference<BuildStatus> status = new AtomicReference<>(BuildStatus.idle());

    public EvalCaseBuilder(JdbcTemplate jdbc, EvalRepository repository, InternalLlm internalLlm) {
        this.jdbc = jdbc;
        this.repository = repository;
        this.internalLlm = internalLlm;
    }

    public BuildStatus status() {
        return status.get();
    }

    public synchronized BuildStatus startGenerate(String suite, int perDocument, String category) {
        if ("RUNNING".equals(status.get().state())) {
            return status.get();
        }
        List<Sample> samples = sampleChunks(Math.max(1, perDocument), category);
        if (samples.isEmpty()) {
            throw new IllegalArgumentException(
                    "Khong co doan tai lieu nao de sinh cau hoi. Nap tai lieu truoc.");
        }
        status.set(new BuildStatus("RUNNING", samples.size(), 0, 0, null));

        Thread worker = new Thread(() -> generate(suite, samples), "eval-case-gen");
        worker.setDaemon(true);
        worker.start();
        return status.get();
    }

    private record Sample(String fileName, String category, String headingPath, String content) {
    }

    private List<Sample> sampleChunks(int perDocument, String category) {
        StringBuilder sql = new StringBuilder("""
                SELECT file_name, category, heading_path, content FROM (
                    SELECT c.file_name, c.category, c.heading_path, c.content,
                           row_number() OVER (PARTITION BY c.doc_id ORDER BY random()) AS rn
                      FROM rag_chunks c
                     WHERE length(c.content) >= 200
                """);
        List<Object> args = new ArrayList<>();
        if (category != null && !category.isBlank()) {
            sql.append(" AND c.category = ? ");
            args.add(category.strip().toLowerCase(Locale.ROOT));
        }
        sql.append(") t WHERE rn <= ? ORDER BY random()");
        args.add(perDocument);

        return jdbc.query(sql.toString(), (rs, i) -> new Sample(
                rs.getString("file_name"), rs.getString("category"),
                rs.getString("heading_path"), rs.getString("content")), args.toArray());
    }

    private void generate(String suite, List<Sample> samples) {
        int done = 0;
        int skipped = 0;
        Set<String> seen = new LinkedHashSet<>();
        String lastError = null;

        for (Sample sample : samples) {
            try {
                String question = askForQuestion(sample);
                if (question == null || question.isBlank()) {
                    skipped++;
                } else if (!seen.add(normalize(question))) {
                    skipped++;
                } else {
                    repository.addCase(new EvalRepository.EvalCase(
                            null, suite, question, sample.fileName(), null,
                            sample.category(), true));
                    done++;
                }
            } catch (Exception e) {
                skipped++;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.debug("Question generation failed for '{}': {}", sample.fileName(), e.getMessage());
            }
            status.set(new BuildStatus("RUNNING", samples.size(), done, skipped, null));
        }

        String message = "Đã sinh " + done + " câu hỏi vào bộ '" + suite + "'"
                + (skipped > 0 ? ", bỏ qua " + skipped + "." : ".");
        if (done == 0) {
            message += lastError != null
                    ? " Nguyên nhân: " + lastError
                      + ". Kiểm tra rag.internal.provider và API key tương ứng."
                    : " Model nội bộ không sinh được câu hỏi nào từ các đoạn đã lấy mẫu"
                      + " (tài liệu có thể chỉ gồm mục lục hoặc bảng trống).";
        }
        status.set(new BuildStatus("DONE", samples.size(), done, skipped, message));
        log.info("Generated eval suite '{}': {} case(s), {} skipped.{}", suite, done, skipped,
                lastError == null ? "" : " Last error: " + lastError);
    }

    private String askForQuestion(Sample sample) {
        String prompt = """
                Duoi day la mot doan trich tu tai lieu noi bo cua cong ty.

                Hay viet DUNG MOT cau hoi bang tieng Viet ma mot can bo trong cong ty se hoi,
                va doan tai lieu nay tra loi duoc.

                YEU CAU:
                - Viet nhu nguoi dung go vao chatbot: ngan, tu nhien, khong trang trong.
                - KHONG sao chep nguyen van cau chu trong doan; dien dat lai bang tu ngu
                  thong thuong.
                - Cau hoi phai TU DUNG DUOC mot minh, khong dung "doan nay", "tai lieu tren".
                - Neu doan chi la muc luc, tieu de hay bang trong khong co thong tin gi de
                  hoi, tra ve dung chu: KHONG
                - CHI tra ve cau hoi, khong giai thich, khong dau ngoac kep.

                MUC: %s

                DOAN TAI LIEU:
                %s
                """.formatted(
                sample.headingPath() == null ? "(khong ro)" : sample.headingPath(),
                abbreviate(sample.content()));

        String answer = internalLlm.generate(prompt);
        if (answer == null) return null;
        String question = answer.strip().replaceAll("^[\"']|[\"']$", "").strip();

        if (question.equalsIgnoreCase("KHONG") || question.length() < 8
                || question.length() > 300) {
            return null;
        }
        return question;
    }

    public Map<String, Object> harvest(String suite, int sinceDays, int limit) {
        List<Object[]> rows = jdbc.query("""
                SELECT u.content AS question, c.file_name, m.id AS message_id
                  FROM rag_messages m
                  JOIN LATERAL (
                        SELECT content FROM rag_messages u
                         WHERE u.conversation_id = m.conversation_id
                           AND u.role = 'user' AND u.id < m.id
                         ORDER BY u.id DESC LIMIT 1
                  ) u ON true
                  JOIN LATERAL (
                        SELECT file_name FROM rag_message_citations
                         WHERE message_id = m.id AND file_name IS NOT NULL
                         ORDER BY rank LIMIT 1
                  ) c ON true
                 WHERE m.role = 'assistant'
                   AND NOT m.abstained
                   AND m.created_at >= now() - make_interval(days => ?)
                   -- Bo cau da bi danh gia xau: khong the lay cai nguoi dung che lam chuan
                   AND NOT EXISTS (SELECT 1 FROM rag_feedback f
                                    WHERE f.message_id = m.id AND f.rating < 0)
                 ORDER BY m.id DESC
                 LIMIT ?
                """, (rs, i) -> new Object[]{
                rs.getString("question"), rs.getString("file_name")},
                sinceDays, limit);

        Set<String> existing = existingQuestions(suite);
        int added = 0;
        int duplicates = 0;
        for (Object[] row : rows) {
            String question = (String) row[0];
            if (question == null || question.isBlank()) continue;
            if (!existing.add(normalize(question))) {
                duplicates++;
                continue;
            }
            repository.addCase(new EvalRepository.EvalCase(
                    null, suite, question.strip(), (String) row[1], null, null, true));
            added++;
        }

        log.info("Harvested eval suite '{}': {} added, {} duplicates.", suite, added, duplicates);
        return Map.of(
                "message", "Đã thêm " + added + " câu hỏi thật vào bộ '" + suite + "'"
                        + (duplicates > 0 ? " (bỏ " + duplicates + " câu trùng)." : "."),
                "added", added,
                "duplicates", duplicates,
                "note", "Nhãn nguồn ở đây là thứ hệ thống ĐÃ tìm ra, không phải thứ đúng. "
                        + "Bộ này đo hồi quy (thay đổi có làm hỏng cái đang chạy tốt không), "
                        + "không đo được hệ thống vốn đã sai từ đầu.");
    }

    public Map<String, Object> harvestNegative(String suite, int sinceDays, int limit) {
        List<String> questions = jdbc.query("""
                SELECT u.content AS question
                  FROM rag_feedback f
                  JOIN rag_messages m ON m.id = f.message_id
                  JOIN LATERAL (
                        SELECT content FROM rag_messages u
                         WHERE u.conversation_id = m.conversation_id
                           AND u.role = 'user' AND u.id < m.id
                         ORDER BY u.id DESC LIMIT 1
                  ) u ON true
                 WHERE f.rating < 0
                   AND f.created_at >= now() - make_interval(days => ?)
                 ORDER BY f.id DESC
                 LIMIT ?
                """, (rs, i) -> rs.getString("question"), sinceDays, limit);

        Set<String> existing = existingQuestions(suite);
        int added = 0;
        for (String question : questions) {
            if (question == null || question.isBlank()) continue;
            if (!existing.add(normalize(question))) continue;
            repository.addCase(new EvalRepository.EvalCase(
                    null, suite, question.strip(), null, null, null, true));
            added++;
        }
        return Map.of(
                "message", "Đã thêm " + added + " câu hỏi bị đánh giá xấu vào bộ '" + suite + "'.",
                "added", added,
                "note", "Các câu này CHƯA có nguồn đúng — cần người điền expectedSource "
                        + "thì mới dùng để đo được. Đây là danh sách việc cần xem lại.");
    }

    private Set<String> existingQuestions(String suite) {
        Set<String> out = new LinkedHashSet<>();
        for (EvalRepository.EvalCase c : repository.listCases(suite, false)) {
            out.add(normalize(c.question()));
        }
        return out;
    }

    static String normalize(String question) {
        if (question == null) return "";
        return com.ai.aiagent.store.TsQueryBuilder.stripDiacritics(question)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String abbreviate(String content) {
        if (content == null) return "";
        return content.length() <= 1500 ? content : content.substring(0, 1500);
    }
}
