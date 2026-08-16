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

/**
 * Tao bo cau hoi chuan MA KHONG CAN AI GAN NHAN TAY.
 *
 * Van de that: doi model embedding hay bat ky tham so truy xuat nao cung can mot bo do,
 * nhung mot bo 100 cau hoi that co gan nhan nguon dung chi co sau vai thang van hanh.
 * Bat phai co no truoc khi trien khai la bai toan con ga - qua trung.
 *
 * Hai duong thoat, khong duong nao can nguoi ngoi gan nhan:
 *
 *  1) {@link #generate} - SINH TU CHINH KHO TAI LIEU. Voi moi doan tai lieu, nho model
 *     noi bo viet mot cau hoi ma doan do tra loi duoc; nguon dung CHINH LA file chua
 *     doan do, nen nhan co san, khong ai phai gan. Dung duoc ngay ngay dau, truoc khi
 *     co nguoi dung nao.
 *
 *  2) {@link #harvest} - THU HOACH TU LOG THAT. Sau khi chay, {@code rag_messages} da
 *     luu cau hoi that kem trich dan; lay cau hoi + nguon da trich lam case. Bo do tu
 *     lon len theo thoi gian van hanh, dung nhu cach no PHAI hinh thanh.
 *
 * Dung ca hai: (1) de co diem xuat phat ngay, (2) de bo do dan phan anh thuc te.
 */
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

    // ============================================================ 1) Sinh tu kho tai lieu

    /**
     * Sinh bo cau hoi tu chinh kho tai lieu.
     *
     * GIOI HAN PHAI BIET: cau hoi duoc sinh TU doan tai lieu nen dung chung tu vung voi
     * doan do => de hon cau hoi that, va con so recall tuyet doi se cao hon thuc te.
     * NHUNG de SO SANH hai cau hinh tren cung mot bo thi do lech nay tac dong nhu nhau
     * len ca hai ben, nen phep so sanh van co gia tri. Dung no de tra loi "cau hinh nao
     * tot hon", dung dung no de tra loi "he thong tot den dau".
     *
     * De giam bot su de dai do, prompt yeu cau dien dat lai theo giong nguoi dung that
     * va cam sao chep nguyen van.
     *
     * @param perDocument so cau hoi lay tu moi tai lieu - rai deu de mot tai lieu lon
     *                    khong chiem het bo do
     */
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

    /**
     * Lay mau RAI DEU theo tai lieu.
     *
     * Neu chi {@code ORDER BY random() LIMIT n}, mot quy che 500 trang se chiem gan het
     * bo do va bo do se do "tim trong mot tai lieu" chu khong phai "tim dung tai lieu".
     */
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
                    skipped++; // hai doan sinh ra cung mot cau hoi
                } else {
                    repository.addCase(new EvalRepository.EvalCase(
                            null, suite, question, sample.fileName(), null,
                            sample.category(), true));
                    done++;
                }
            } catch (Exception e) {
                // Mot cau loi khong duoc lam sap ca lan sinh, nhung PHAI giu lai ly do:
                // khi khong sinh duoc case nao, "bo qua 200" ma khong noi vi sao la thong
                // bao vo dung - nguoi van hanh khong biet la thieu API key hay tai lieu xau.
                skipped++;
                lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
                log.debug("Sinh cau hoi loi cho '{}': {}", sample.fileName(), e.getMessage());
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
        log.info("Sinh bo cau hoi '{}': {} case, bo qua {}.{}", suite, done, skipped,
                lastError == null ? "" : " Loi gan nhat: " + lastError);
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

    // ============================================================ 2) Thu hoach tu log

    /**
     * Lay cau hoi THAT tu lich su hoi thoai lam case moi.
     *
     * NGUON NHAN: nguon ma he thong DA trich dan cho cau tra loi khong bi tu choi va
     * khong bi danh gia xau.
     *
     * PHAI HIEU DUNG NO DO GI: nhan o day la "he thong tung tim ra cai gi", khong phai
     * "cau tra loi dung la cai gi". Vi vay bo nay do duoc HOI QUY - mot thay doi co lam
     * hong nhung gi truoc day chay tot khong - nhung KHONG noi duoc he thong tu dau da
     * sai. Do la ly do van nen bo sung cau hoi bi 👎 vao de nguoi that xem lai.
     *
     * @param sinceDays chi lay hoi thoai trong N ngay gan nhat
     * @param limit     so case toi da them
     */
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

        log.info("Thu hoach bo cau hoi '{}': them {}, trung {}.", suite, added, duplicates);
        return Map.of(
                "message", "Đã thêm " + added + " câu hỏi thật vào bộ '" + suite + "'"
                        + (duplicates > 0 ? " (bỏ " + duplicates + " câu trùng)." : "."),
                "added", added,
                "duplicates", duplicates,
                "note", "Nhãn nguồn ở đây là thứ hệ thống ĐÃ tìm ra, không phải thứ đúng. "
                        + "Bộ này đo hồi quy (thay đổi có làm hỏng cái đang chạy tốt không), "
                        + "không đo được hệ thống vốn đã sai từ đầu.");
    }

    /**
     * Cau hoi bi danh gia xau, dua vao mot bo RIENG va KHONG gan nhan nguon.
     *
     * Day la danh sach viec can nguoi xem lai: chi nguoi moi biet cau tra loi dung phai
     * lay tu dau. Tach bo rieng de khong lam ban bo do hoi quy.
     */
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

    // ============================================================ Tro giup

    private Set<String> existingQuestions(String suite) {
        Set<String> out = new LinkedHashSet<>();
        for (EvalRepository.EvalCase c : repository.listCases(suite, false)) {
            out.add(normalize(c.question()));
        }
        return out;
    }

    /** Khu trung theo cau hoi da bo dau, chu thuong - "Nghi phep?" va "nghỉ phép" la mot. */
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
