package com.ai.aiagent.retrieval;

/**
 * Detects chunks that are a table of contents rather than content.
 *
 * <p>A Word specification starts with several pages of "2.2.5 Giai phap thuc hien 21" lines. They
 * chunk like any other text, they embed close to every question about the document because they
 * repeat its section titles, and they carry no answer at all. Measured on UAT the reranker gave
 * such a chunk 0.92 - the top slot - for "Lenh STO", pushing the actual specification down the
 * prompt.
 *
 * <p>Detection is deliberately narrow: a numbered section label or a dotted leader, plus a
 * trailing page number, on most lines of a chunk that has several such lines. Prose that merely
 * ends a sentence in a number does not qualify.
 *
 * <p>Hand-written scanning rather than regular expressions, and not by preference. The first
 * version used {@code ^\s*\d+(?:\.\d+)+\.?\s+\S.*?\s+\d{1,4}\s*$}; the lazy {@code .*?} in front
 * of a trailing anchor backtracks quadratically on every line that does NOT match, which is most
 * of them. On real candidate sets that turned a 600 ms retrieval into 26 s. Each check below is a
 * single left-to-right pass.
 */
public final class TableOfContentsFilter {

    private static final int MIN_ENTRIES = 5;
    private static final double MIN_SHARE = 0.6;

    private TableOfContentsFilter() {
    }

    public static boolean isTableOfContents(String text) {
        if (text == null || text.isBlank()) return false;

        int considered = 0;
        int entries = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            // The chunker prefixes the heading path as a Markdown heading; it is not content.
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            considered++;
            if (isEntry(trimmed)) entries++;
        }

        return entries >= MIN_ENTRIES && considered > 0
                && (double) entries / considered >= MIN_SHARE;
    }

    private static boolean isEntry(String line) {
        return endsWithPageNumber(line)
                && (startsWithSectionNumber(line) || hasDottedLeader(line));
    }

    /** A short number at the end, set off from the text by a space or a leader dot. */
    private static boolean endsWithPageNumber(String line) {
        int i = line.length() - 1;
        int digits = 0;
        while (i >= 0 && Character.isDigit(line.charAt(i))) {
            i--;
            digits++;
        }
        if (digits == 0 || digits > 4) return false;
        if (i < 0) return false;
        char before = line.charAt(i);
        return Character.isWhitespace(before) || before == '.';
    }

    /** "2.3" or "2.2.5." followed by whitespace - at least two levels, so "1. " is not enough. */
    private static boolean startsWithSectionNumber(String line) {
        int i = 0;
        int len = line.length();
        while (i < len && Character.isDigit(line.charAt(i))) i++;
        if (i == 0) return false;

        int levels = 0;
        while (i < len && line.charAt(i) == '.') {
            int j = i + 1;
            while (j < len && Character.isDigit(line.charAt(j))) j++;
            if (j == i + 1) break;
            i = j;
            levels++;
        }
        if (levels == 0) return false;

        if (i < len && line.charAt(i) == '.') i++;
        return i < len && Character.isWhitespace(line.charAt(i));
    }

    /** Four or more consecutive dots: "Dieu 5 ......... 12". */
    private static boolean hasDottedLeader(String line) {
        int run = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '.' || c == '…') {
                if (++run >= 4) return true;
            } else {
                run = 0;
            }
        }
        return false;
    }
}
