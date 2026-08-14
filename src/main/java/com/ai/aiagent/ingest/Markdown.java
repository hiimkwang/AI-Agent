package com.ai.aiagent.ingest;

import java.util.List;
import java.util.regex.Pattern;

/** Tien ich sinh va don dep Markdown, dung chung cho moi bo chuyen doi. */
public final class Markdown {

    private static final Pattern MANY_BLANK_LINES = Pattern.compile("\\n{3,}");
    private static final Pattern TRAILING_SPACES = Pattern.compile("[ \\t]+\\n");
    private static final Pattern NBSP = Pattern.compile("[\\u00A0\\u2007\\u202F]");

    private Markdown() {
    }

    /**
     * Dung mot bang Markdown dang pipe.
     *
     * Day la mau chot voi tai lieu noi bo: bieu phi, bang luong, ma tran phan quyen.
     * Truoc day POI lam phang bang thanh text nen mat het quan he hang-cot va he
     * thong gan nhu khong the tra loi dung cac cau hoi dua tren bang.
     */
    public static String table(List<List<String>> rows) {
        if (rows == null || rows.isEmpty()) return "";
        int columns = rows.stream().mapToInt(List::size).max().orElse(0);
        if (columns == 0) return "";

        StringBuilder sb = new StringBuilder();
        List<String> header = pad(rows.get(0), columns);
        sb.append("| ").append(String.join(" | ", header)).append(" |\n");
        sb.append("|").append(" --- |".repeat(columns)).append('\n');

        for (int i = 1; i < rows.size(); i++) {
            sb.append("| ").append(String.join(" | ", pad(rows.get(i), columns))).append(" |\n");
        }
        return sb.toString();
    }

    private static List<String> pad(List<String> row, int columns) {
        List<String> out = new java.util.ArrayList<>(columns);
        for (int i = 0; i < columns; i++) {
            out.add(i < row.size() ? cell(row.get(i)) : "");
        }
        return out;
    }

    /** Escape ky tu lam vo cau truc bang, va gop xuong dong thanh {@code <br>}. */
    public static String cell(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("|", "\\|")
                .replaceAll("\\s*\\r?\\n\\s*", "<br>")
                .trim();
    }

    public static String heading(int level, String text) {
        int l = Math.max(1, Math.min(6, level));
        return "#".repeat(l) + " " + text.trim() + "\n\n";
    }

    /** Chuan hoa: bo NBSP, bo space cuoi dong, gop nhieu dong trong lien tiep. */
    public static String normalize(String markdown) {
        if (markdown == null) return "";
        String out = markdown.replace("\r\n", "\n").replace('\r', '\n');
        out = NBSP.matcher(out).replaceAll(" ");
        out = TRAILING_SPACES.matcher(out).replaceAll("\n");
        out = MANY_BLANK_LINES.matcher(out).replaceAll("\n\n");
        return out.strip() + "\n";
    }

    /** Cat bot neu vuot gioi han, cat theo ranh gioi dong cho de doc. */
    public static String truncate(String markdown, int maxChars) {
        if (markdown == null || markdown.length() <= maxChars) return markdown;
        int cut = markdown.lastIndexOf('\n', maxChars);
        if (cut < maxChars / 2) cut = maxChars;
        return markdown.substring(0, cut)
                + "\n\n> _[Tai lieu bi cat bot vi vuot gioi han "
                + maxChars + " ky tu]_\n";
    }
}
