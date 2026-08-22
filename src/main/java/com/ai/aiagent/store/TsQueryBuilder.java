package com.ai.aiagent.store;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class TsQueryBuilder {

    // Set.copyOf, not Set.of: the list has duplicates and Set.of would throw
    // IllegalArgumentException in the static initializer, killing full-text search.
    private static final Set<String> STOPWORDS = Set.copyOf(java.util.List.of(
            "va", "la", "cua", "cho", "cac", "nhung", "mot", "co", "duoc", "trong",
            "voi", "khi", "thi", "ma", "nay", "do", "o", "tu", "den", "ve", "theo",
            "nhu", "sao", "gi", "nao", "bao", "nhieu", "the", "se", "da", "dang",
            "khong", "phai", "cung", "hay", "hoac", "neu", "vi", "boi", "tai", "ra",
            "vao", "len", "xuong", "roi", "chi", "con", "lai", "nua", "rat", "qua",
            "toi", "ban", "minh", "ai", "dau", "may", "bang", "tren", "duoi", "giua",
            "the", "a", "an", "and", "or", "of", "to", "in", "for", "on", "is", "are",
            "what", "which", "how", "when", "where", "who", "why", "be", "was", "were",
            "this", "that", "it", "as", "at", "by", "with", "from", "can", "does"
    ));

    private static final int MAX_TERMS = 32;

    private TsQueryBuilder() {
    }

    // Terms joined with OR, not AND. plainto_tsquery ANDs them, which made the
    // full-text branch return almost nothing.
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

    public static List<String> terms(String text) {
        if (text == null || text.isBlank()) return List.of();
        String normalized = stripDiacritics(text).toLowerCase();
        Set<String> out = new LinkedHashSet<>();
        for (String raw : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (raw.isEmpty()) continue;
            String term = raw.replaceAll("[^\\p{L}\\p{N}]", "");
            if (term.length() < 2) continue;
            if (STOPWORDS.contains(term)) continue;
            out.add(term);
            if (out.size() >= MAX_TERMS) break;
        }
        return new ArrayList<>(out);
    }

    public static String stripDiacritics(String input) {
        String normalized = Normalizer.normalize(input, Normalizer.Form.NFD);
        return normalized.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd').replace('Đ', 'D');
    }
}
