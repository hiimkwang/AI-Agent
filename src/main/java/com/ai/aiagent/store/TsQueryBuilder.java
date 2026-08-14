package com.ai.aiagent.store;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Dung tsquery cho nhanh full-text.
 *
 * Day la cho sua mot loi lam nhanh full-text gan nhu VO DUNG truoc day:
 * {@code plainto_tsquery} ghep tat ca tu bang AND, nen cau hoi dai - dac biet sau
 * khi query rewrite lam no dai them - chi can MOT tu khong xuat hien trong tai lieu
 * la tra ve 0 ket qua. Hybrid search am tham thoai hoa thanh vector-only.
 *
 * Cach lam moi:
 *   - bo dau va chuan hoa ve chu thuong (config 'vi' co unaccent nen khop ca hai chieu)
 *   - bo tu dung (stopword) tieng Viet va tieng Anh
 *   - ghep cac tu con lai bang OR ({@code |}) -> tai lieu khop NHIEU tu se duoc
 *     {@code ts_rank_cd} xep cao hon, thay vi bi loai thang
 *
 * An toan: moi tu duoc loc chi con chu va so, nen khong the chen toan tu tsquery.
 */
public final class TsQueryBuilder {

    /**
     * Stopword da bo dau, so sanh sau khi normalize.
     *
     * Dung {@code Set.copyOf(List.of(...))} chu KHONG dung {@code Set.of(...)}:
     * {@code Set.of} nem {@code IllegalArgumentException} khi co phan tu trung, va
     * vi day la static initializer nen loi bien thanh {@code ExceptionInInitializerError}
     * lam chet ca nhanh full-text. Danh sach Viet va Anh co tu trung nhau
     * ("the", "do") nen phai cho phep trung.
     */
    private static final Set<String> STOPWORDS = Set.copyOf(java.util.List.of(
            // tieng Viet
            "va", "la", "cua", "cho", "cac", "nhung", "mot", "co", "duoc", "trong",
            "voi", "khi", "thi", "ma", "nay", "do", "o", "tu", "den", "ve", "theo",
            "nhu", "sao", "gi", "nao", "bao", "nhieu", "the", "se", "da", "dang",
            "khong", "phai", "cung", "hay", "hoac", "neu", "vi", "boi", "tai", "ra",
            "vao", "len", "xuong", "roi", "chi", "con", "lai", "nua", "rat", "qua",
            "toi", "ban", "minh", "ai", "dau", "may", "bang", "tren", "duoi", "giua",
            // tieng Anh
            "the", "a", "an", "and", "or", "of", "to", "in", "for", "on", "is", "are",
            "what", "which", "how", "when", "where", "who", "why", "be", "was", "were",
            "this", "that", "it", "as", "at", "by", "with", "from", "can", "does"
    ));

    private static final int MAX_TERMS = 32;

    private TsQueryBuilder() {
    }

    /**
     * @return bieu thuc tsquery dang {@code 'a' | 'b' | 'c'}, hoac null neu khong
     *         con tu nao co nghia (caller nen bo qua nhanh full-text)
     */
    public static String orQuery(String text) {
        List<String> terms = terms(text);
        if (terms.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String term : terms) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append('\'').append(term).append('\'');
        }
        return sb.toString();
    }

    /** Cac tu co nghia da chuan hoa, dung ca cho highlight va cho do trung khop. */
    public static List<String> terms(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = stripDiacritics(text).toLowerCase();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (raw.isEmpty()) continue;
            // Chi giu chu va so -> khong the chen toan tu tsquery
            String term = raw.replaceAll("[^\\p{L}\\p{N}]", "");
            if (term.length() < 2) continue;
            if (STOPWORDS.contains(term)) continue;
            out.add(term);
            if (out.size() >= MAX_TERMS) break;
        }
        return new ArrayList<>(out);
    }

    /** Bo dau tieng Viet: "nghỉ phép" -> "nghi phep". */
    public static String stripDiacritics(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
    }
}
