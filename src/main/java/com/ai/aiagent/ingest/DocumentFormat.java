package com.ai.aiagent.ingest;

import java.util.Locale;
import java.util.Set;

public enum DocumentFormat {

    MARKDOWN("md", "markdown", "mdx"),
    HTML("html", "htm", "xhtml"),
    PDF("pdf"),
    DOCX("docx"),
    DOC("doc"),
    XLSX("xlsx", "xlsm"),
    XLS("xls"),
    PPTX("pptx"),
    PPT("ppt"),
    TEXT("txt", "text", "log", "csv", "tsv", "json", "yaml", "yml");

    private final Set<String> extensions;

    DocumentFormat(String... extensions) {
        this.extensions = Set.of(extensions);
    }

    public boolean isOffice() {
        return this == DOCX || this == DOC || this == XLSX || this == XLS || this == PPTX || this == PPT;
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) return "";
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static DocumentFormat fromFileName(String fileName) {
        String ext = extensionOf(fileName);
        if (ext.isEmpty()) return null;
        for (DocumentFormat f : values()) {
            if (f.extensions.contains(ext)) return f;
        }
        return null;
    }

    public static boolean isSupported(String fileName) {
        return fromFileName(fileName) != null;
    }

    public static java.util.List<String> allExtensions() {
        return java.util.Arrays.stream(values())
                .flatMap(f -> f.extensions.stream())
                .sorted()
                .toList();
    }
}
