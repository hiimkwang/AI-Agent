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

@Service
@Slf4j
public class GlossaryService {

    public record Entry(String term, List<String> expansions, String collectionSlug) {
    }

    private final JdbcTemplate jdbc;
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
            log.warn("Could not load the glossary ({}). Retrying in one minute.",
                    e.getMessage());
        }
    }

    public boolean isEmpty() {
        return byTerm.isEmpty();
    }

    public List<Entry> all() {
        return entries;
    }

    public Set<String> expand(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isBlank() || byTerm.isEmpty()) return out;

        String normalized = normalize(text);
        for (Map.Entry<String, List<String>> e : byTerm.entrySet()) {
            if (containsWord(normalized, e.getKey())) {
                out.addAll(e.getValue());
            }
        }
        return out;
    }

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

    // Word-boundary match, not contains(): "ky" must not match inside "kyq".
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

    static String normalize(String text) {
        if (text == null) return "";
        return com.ai.aiagent.store.TsQueryBuilder.stripDiacritics(text)
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ")
                .strip();
    }
}
