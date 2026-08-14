package com.ai.aiagent.ingest;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Doc khoi front-matter dau file Markdown.
 *
 * Vi ban se tu chuyen tai lieu sang Markdown truoc khi nap, day la cho de ban
 * KHAI BAO METADATA ma he thong khong the tu suy ra duoc:
 *
 * <pre>
 * ---
 * title: Noi quy lao dong 2026
 * category: nhan-su
 * department: nhan-su
 * doc_number: 12/2026/QD-BSC
 * version: 2.1
 * effective_date: 2026-01-01
 * expires_date: 2027-12-31
 * status: ACTIVE
 * roles: HR, ADMIN
 * ---
 * </pre>
 *
 * Nho {@code effective_date} + {@code status}, he thong moi tra loi duoc "quy dinh
 * MOI NHAT la gi" va khong con trich dan van ban da het hieu luc ngang hang voi
 * van ban dang ap dung.
 */
@Slf4j
public final class FrontMatter {

    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("d/M/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd")
    };

    /**
     * @param fields   cac cap key/value da doc duoc (key da lowercase)
     * @param body     phan Markdown con lai sau khi bo front-matter
     * @param hadBlock co ton tai khoi front-matter hay khong
     */
    public record Parsed(Map<String, String> fields, String body, boolean hadBlock) {

        public String text(String key) {
            String v = fields.get(key);
            return v == null || v.isBlank() ? null : v.strip();
        }

        public LocalDate date(String key) {
            return parseDate(text(key));
        }

        public List<String> list(String key) {
            String v = text(key);
            if (v == null) return List.of();
            List<String> out = new ArrayList<>();
            for (String part : v.replace("[", "").replace("]", "").split(",")) {
                String s = part.trim().replaceAll("^[\"']|[\"']$", "");
                if (!s.isEmpty()) out.add(s);
            }
            return out;
        }
    }

    private FrontMatter() {
    }

    public static Parsed parse(String markdown) {
        if (markdown == null) return new Parsed(Map.of(), "", false);
        String text = markdown.replace("\r\n", "\n").stripLeading();
        if (!text.startsWith("---")) {
            return new Parsed(Map.of(), markdown, false);
        }
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) return new Parsed(Map.of(), markdown, false);

        int end = text.indexOf("\n---", firstBreak);
        if (end < 0) {
            return new Parsed(Map.of(), markdown, false);
        }
        String block = text.substring(firstBreak + 1, end);
        int bodyStart = text.indexOf('\n', end + 1);
        String body = bodyStart < 0 ? "" : text.substring(bodyStart + 1);

        Map<String, String> fields = new LinkedHashMap<>();
        for (String line : block.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            int colon = trimmed.indexOf(':');
            if (colon <= 0) continue;
            String key = trimmed.substring(0, colon).strip().toLowerCase().replace('-', '_');
            String value = trimmed.substring(colon + 1).strip()
                    .replaceAll("^[\"']|[\"']$", "");
            if (!key.isEmpty()) fields.put(key, value);
        }
        return new Parsed(fields, body, true);
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        String v = value.strip();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // thu dinh dang tiep theo
            }
        }
        log.debug("Khong doc duoc ngay '{}' - bo qua.", value);
        return null;
    }
}
