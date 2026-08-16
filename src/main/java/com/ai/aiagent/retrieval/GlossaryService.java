package com.ai.aiagent.retrieval;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Array;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Tu dien thuat ngu / viet tat noi bo.
 *
 * VAN DE NO GIAI: can bo go "UBCK", "CTCK", "margin"; tai lieu viet "Uy ban Chung
 * khoan Nha nuoc", "cong ty chung khoan", "giao dich ky quy". Vector search khong
 * noi duoc cac cap nay mot cach dang tin, con full-text thi cang khong - hai chuoi
 * khong chung mot tu nao. Ket qua la cau hoi go tat gan nhu chac chan truot.
 *
 * Dung o HAI cho, co y:
 *   1) {@link #expand} - mo rong tsquery cua nhanh full-text (khop ngay lap tuc)
 *   2) {@link #hintFor} - chen vao prompt viet lai cau hoi, de cau viet lai dung
 *      dung thuat ngu cua tai lieu (giup ca nhanh vector)
 *
 * Bang rat nho nen giu ban chup trong bo nho, lam moi moi phut - giong
 * {@code PlatformService}.
 */
@Service
@Slf4j
public class GlossaryService {

    /** @param expansions cac cach viet khac cua {@code term}, da chuan hoa chu thuong */
    public record Entry(String term, List<String> expansions, String collectionSlug) {
    }

    private final JdbcTemplate jdbc;
    /** khoa = term chu thuong (khong dau), gia tri = cac ban mo rong */
    private volatile Map<String, List<String>> byTerm = Map.of();
    private volatile List<Entry> entries = List.of();

    public GlossaryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        refreshQuietly();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refreshScheduled() {
        refreshQuietly();
    }

    public void refresh() {
        List<Entry> loaded = jdbc.query(
                "SELECT term, expansions, collection_slug FROM rag_synonyms",
                (rs, i) -> {
                    Array array = rs.getArray("expansions");
                    String[] values = array == null ? new String[0] : (String[]) array.getArray();
                    return new Entry(rs.getString("term"), List.of(values),
                            rs.getString("collection_slug"));
                });

        Map<String, List<String>> index = new LinkedHashMap<>();
        for (Entry e : loaded) {
            // Khoa tra cuu da bo dau: nguoi dung go "ky quy" phai khop "ký quỹ".
            String key = normalize(e.term());
            if (key.isBlank()) continue;
            index.computeIfAbsent(key, k -> new ArrayList<>()).addAll(e.expansions());
        }
        this.entries = List.copyOf(loaded);
        this.byTerm = Map.copyOf(index);
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            // Luc khoi dong, Flyway co the chua chay xong. Tu dien rong chi lam mat mot
            // cai thien, khong duoc lam chet ung dung.
            log.warn("Chua nap duoc tu dien thuat ngu ({}). Se thu lai sau 1 phut.",
                    e.getMessage());
        }
    }

    public boolean isEmpty() {
        return byTerm.isEmpty();
    }

    public List<Entry> all() {
        return entries;
    }

    /**
     * Cac cach viet khac cua nhung thuat ngu xuat hien trong cau hoi.
     *
     * @return chuoi da them, KHONG gom cac tu goc - caller tu gop
     */
    public Set<String> expand(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank() || byTerm.isEmpty()) return out;

        String normalized = normalize(text);
        for (Map.Entry<String, List<String>> e : byTerm.entrySet()) {
            // So khop theo RANH GIOI TU, khong phai contains(): "kyq" khong duoc khop
            // trong "kyquyet", va "nd" khong khop moi tu chua "nd".
            if (containsWord(normalized, e.getKey())) {
                out.addAll(e.getValue());
            }
        }
        return out;
    }

    /**
     * Goi y thuat ngu de chen vao prompt viet lai cau hoi.
     *
     * @return chuoi rong khi cau hoi khong chua thuat ngu nao - de khong lam phong
     *         prompt mot cach vo ich
     */
    public String hintFor(String question) {
        if (byTerm.isEmpty()) return "";
        String normalized = normalize(question);
        StringBuilder sb = new StringBuilder();
        for (Entry e : entries) {
            if (!containsWord(normalized, normalize(e.term()))) continue;
            if (e.expansions().isEmpty()) continue;
            sb.append("- ").append(e.term()).append(" = ")
                    .append(String.join(" / ", e.expansions())).append('\n');
        }
        return sb.toString();
    }

    /** So khop theo ranh gioi tu tren chuoi da chuan hoa. */
    static boolean containsWord(String haystack, String needle) {
        if (needle.isBlank()) return false;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) return false;
            boolean leftOk = at == 0 || !Character.isLetterOrDigit(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean rightOk = end >= haystack.length()
                    || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
    }

    /**
     * Chuan hoa de so khop: bo dau, chu thuong, gop khoang trang.
     *
     * CO Y giu dau {@code +} va so: "T+2" la mot thuat ngu that trong nganh chung khoan.
     */
    static String normalize(String text) {
        if (text == null) return "";
        return com.ai.aiagent.store.TsQueryBuilder.stripDiacritics(text)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }
}
