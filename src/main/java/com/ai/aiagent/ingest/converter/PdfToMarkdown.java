package com.ai.aiagent.ingest.converter;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.ingest.Markdown;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
@Slf4j
public class PdfToMarkdown {

    private static final Pattern VN_LEVEL_1 = Pattern.compile(
            "^\\s*(PH\\p{L}N|CH\\p{L}\\p{L}NG|PART|CHAPTER)\\s+([IVXLCDM]+|\\d+)\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VN_LEVEL_2 = Pattern.compile(
            "^\\s*(M\\p{L}C|\\p{L}I\\p{L}U|SECTION|ARTICLE)\\s+(\\d+|[IVXLCDM]+)\\b.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMBERED = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+){0,3})\\.?\\s+\\p{Lu}.{2,120}$");
    private static final Pattern APPENDIX = Pattern.compile(
            "^\\s*(PH\\p{L}\\s*L\\p{L}C|APPENDIX|ANNEX)\\b.*", Pattern.CASE_INSENSITIVE);

    private final RagProperties props;

    public PdfToMarkdown(RagProperties props) {
        this.props = props;
    }

    public String convert(byte[] bytes, String fileName) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                log.warn("PDF '{}' is encrypted, only unprotected text could be extracted.", fileName);
            }
            LineCollector collector = new LineCollector();
            collector.setSortByPosition(true);
            collector.setStartPage(1);
            collector.setEndPage(document.getNumberOfPages());
            collector.getText(document);

            List<Line> lines = collector.lines;
            if (lines.isEmpty()) {
                log.warn("PDF '{}' yielded no text; it is probably a scan and needs OCR.", fileName);
                return "";
            }

            if (props.getConvert().isPdfDropRepeatedLines()) {
                dropRepeatedHeadersFooters(lines, document.getNumberOfPages());
            }

            double bodySize = medianFontSize(lines);
            return Markdown.normalize(render(lines, bodySize, fileName));
        } catch (IOException e) {
            throw new IllegalStateException("Khong doc duoc PDF '" + fileName + "': " + e.getMessage(), e);
        }
    }

    private record Line(int page, String text, double fontSize, boolean bold) {
    }

    private static class LineCollector extends PDFTextStripper {
        private final List<Line> lines = new ArrayList<>();

        LineCollector() throws IOException {
            super();
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            String trimmed = text.strip();
            if (trimmed.isEmpty()) return;

            double sizeSum = 0;
            int boldCount = 0;
            int count = 0;
            for (TextPosition p : positions) {
                if (Character.isWhitespace(p.getUnicode().isEmpty() ? ' ' : p.getUnicode().charAt(0))) continue;
                sizeSum += p.getFontSizeInPt();
                count++;
                try {
                    String font = p.getFont().getName();
                    if (font != null) {
                        String f = font.toLowerCase();
                        if (f.contains("bold") || f.contains("black") || f.contains("heavy")) boldCount++;
                    }
                } catch (RuntimeException ignored) {
                }
            }
            double avgSize = count == 0 ? 0 : sizeSum / count;
            boolean bold = count > 0 && boldCount > count / 2;
            lines.add(new Line(getCurrentPageNo(), trimmed, avgSize, bold));
        }
    }

    private double medianFontSize(List<Line> lines) {
        List<Double> sizes = lines.stream()
                .filter(l -> l.text().length() > 60 && l.fontSize() > 0)
                .map(Line::fontSize)
                .sorted()
                .toList();
        if (sizes.isEmpty()) {
            sizes = lines.stream().map(Line::fontSize).filter(s -> s > 0).sorted().toList();
        }
        if (sizes.isEmpty()) return 0;
        return sizes.get(sizes.size() / 2);
    }

    private void dropRepeatedHeadersFooters(List<Line> lines, int pageCount) {
        if (pageCount < 3) return;

        Map<String, java.util.Set<Integer>> pagesByText = new HashMap<>();
        for (Line l : lines) {
            if (l.text().length() > 120) continue;
            pagesByText.computeIfAbsent(normalizeForRepeat(l.text()), k -> new java.util.HashSet<>())
                    .add(l.page());
        }
        int threshold = (int) Math.ceil(pageCount * 0.6);
        java.util.Set<String> repeated = new java.util.HashSet<>();
        pagesByText.forEach((text, pages) -> {
            if (pages.size() >= threshold && !text.isBlank()) repeated.add(text);
        });
        if (repeated.isEmpty()) return;

        int before = lines.size();
        lines.removeIf(l -> repeated.contains(normalizeForRepeat(l.text())));
        log.debug("Dropped {} repeated header/footer line(s) ({} distinct patterns).",
                    before - lines.size(), repeated.size());
    }

    private String normalizeForRepeat(String text) {
        return text.replaceAll("\\d+", "#").strip().toLowerCase();
    }

    private String render(List<Line> lines, double bodySize, String fileName) {
        StringBuilder sb = new StringBuilder();
        sb.append(Markdown.heading(1, stripExtension(fileName)));

        int currentPage = -1;
        StringBuilder paragraph = new StringBuilder();

        for (Line line : lines) {
            int headingLevel = headingLevelOf(line, bodySize);

            if (line.page() != currentPage) {
                currentPage = line.page();
                flush(sb, paragraph);
                sb.append("\n<!-- page ").append(currentPage).append(" -->\n\n");
            }

            if (headingLevel > 0) {
                flush(sb, paragraph);
                sb.append(Markdown.heading(headingLevel, line.text()));
            } else if (looksLikeListItem(line.text())) {
                flush(sb, paragraph);
                sb.append("- ").append(line.text().replaceFirst("^\\s*[-•·*\\u2022]\\s*", "")).append('\n');
            } else {
                if (paragraph.length() > 0) paragraph.append(' ');
                paragraph.append(line.text());
                if (line.text().matches(".*[.;:!?]\\s*$")) {
                    flush(sb, paragraph);
                }
            }
        }
        flush(sb, paragraph);
        return sb.toString();
    }

    private void flush(StringBuilder sb, StringBuilder paragraph) {
        if (paragraph.length() == 0) return;
        sb.append(paragraph.toString().strip()).append("\n\n");
        paragraph.setLength(0);
    }

    private int headingLevelOf(Line line, double bodySize) {
        String text = line.text();
        if (text.length() > 160) return 0;

        if (VN_LEVEL_1.matcher(text).matches()) return 2;
        if (VN_LEVEL_2.matcher(text).matches()) return 3;
        if (APPENDIX.matcher(text).matches()) return 2;
        if (NUMBERED.matcher(text).matches()) {
            String number = text.strip().split("[\\s.]")[0];
            long depth = number.chars().filter(c -> c == '.').count();
            return (int) Math.min(4, 3 + depth);
        }

        if (bodySize > 0 && line.fontSize() >= bodySize * 1.35) return 2;
        if (bodySize > 0 && line.fontSize() >= bodySize * 1.15 && line.bold()) return 3;

        if (text.length() <= 80 && text.equals(text.toUpperCase())
                && text.matches(".*\\p{L}.*") && !text.matches(".*[.;:!?]\\s*$")) {
            return 3;
        }
        return 0;
    }

    private boolean looksLikeListItem(String text) {
        return text.matches("^\\s*[-•·*\\u2022]\\s+\\S.*")
                || text.matches("^\\s*[a-z]\\)\\s+\\S.*");
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
