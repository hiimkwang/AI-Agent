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

/**
 * PDF -> Markdown, co suy ra CAU TRUC thay vi chi bóc text phang.
 *
 * PDF khong luu heading, nen ta suy ra tu hai tin hieu doc lap:
 *
 *  1) CO CHU: dong nao co font lon han han so voi co chu than bai thi la heading.
 *     Ti le so voi median quyet dinh cap (h1/h2/h3).
 *  2) MAU SO THU TU: rat hieu qua voi van ban phap quy tieng Viet -
 *     "PHAN I", "Chuong II", "Muc 3", "Dieu 12.", "1.2.3 ..." - dung duoc ca khi
 *     thong tin font khong dang tin (PDF scan-to-text, PDF xuat tu Word cu).
 *
 * Ngoai ra tu dong nhan va BO header/footer lap lai giua cac trang (so trang, ten
 * cong ty, duong dan file...) - neu khong, chung se lot vao gan nhu moi chunk.
 */
@Component
@Slf4j
public class PdfToMarkdown {

    /** Cap 1: PHAN / CHUONG. */
    private static final Pattern VN_LEVEL_1 = Pattern.compile(
            "^\\s*(PH\\p{L}N|CH\\p{L}\\p{L}NG|PART|CHAPTER)\\s+([IVXLCDM]+|\\d+)\\b.*",
            Pattern.CASE_INSENSITIVE);
    /** Cap 2: MUC / DIEU. */
    private static final Pattern VN_LEVEL_2 = Pattern.compile(
            "^\\s*(M\\p{L}C|\\p{L}I\\p{L}U|SECTION|ARTICLE)\\s+(\\d+|[IVXLCDM]+)\\b.*",
            Pattern.CASE_INSENSITIVE);
    /** Cap 3: 1. / 1.2 / 1.2.3 theo sau la chu. */
    private static final Pattern NUMBERED = Pattern.compile(
            "^\\s*(\\d+(?:\\.\\d+){0,3})\\.?\\s+\\p{Lu}.{2,120}$");
    /** Cap 3: PHU LUC / APPENDIX. */
    private static final Pattern APPENDIX = Pattern.compile(
            "^\\s*(PH\\p{L}\\s*L\\p{L}C|APPENDIX|ANNEX)\\b.*", Pattern.CASE_INSENSITIVE);

    private final RagProperties props;

    public PdfToMarkdown(RagProperties props) {
        this.props = props;
    }

    public String convert(byte[] bytes, String fileName) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) {
                log.warn("PDF '{}' bi ma hoa - chi lay duoc phan text khong bao ve.", fileName);
            }
            LineCollector collector = new LineCollector();
            collector.setSortByPosition(true);
            collector.setStartPage(1);
            collector.setEndPage(document.getNumberOfPages());
            collector.getText(document); // ket qua duoc gom vao collector.lines

            List<Line> lines = collector.lines;
            if (lines.isEmpty()) {
                log.warn("PDF '{}' khong boc duoc text nao. Co the la ban scan -> can OCR.", fileName);
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

    /** Ket qua boc duoc tu 1 dong van ban trong PDF. */
    private record Line(int page, String text, double fontSize, boolean bold) {
    }

    /**
     * PDFTextStripper mac dinh chi tra ve String. Ta override {@code writeString}
     * de doc thong tin font cua tung dong - day la cach duy nhat lay duoc co chu.
     */
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
                    // Font co the bi loi trong PDF hong - khong sao, coi nhu khong bold
                }
            }
            double avgSize = count == 0 ? 0 : sizeSum / count;
            boolean bold = count > 0 && boldCount > count / 2;
            lines.add(new Line(getCurrentPageNo(), trimmed, avgSize, bold));
        }
    }

    /** Co chu than bai = median co chu cua cac dong dai (dong ngan hay la tieu de). */
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

    /**
     * Bo header/footer: dong xuat hien o >= 60% so trang (va tai liệu co >= 3 trang)
     * gan nhu chac chan la header/footer chu khong phai noi dung.
     */
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
        log.debug("PDF: bo {} dong header/footer lap lai ({} mau).", before - lines.size(), repeated.size());
    }

    /** Thay so bang # de "Trang 3/20" va "Trang 4/20" duoc coi la cung mot mau. */
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
                // Danh dau trang duoi dang comment: giup truy nguon "trang may"
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
                // Dong ket thuc bang dau cau => het doan
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

    /** @return 0 neu khong phai heading, nguoc lai la cap heading 2..4. */
    private int headingLevelOf(Line line, double bodySize) {
        String text = line.text();
        if (text.length() > 160) return 0;

        // Tin hieu 1: mau so thu tu (dang tin nhat voi van ban phap quy)
        if (VN_LEVEL_1.matcher(text).matches()) return 2;
        if (VN_LEVEL_2.matcher(text).matches()) return 3;
        if (APPENDIX.matcher(text).matches()) return 2;
        if (NUMBERED.matcher(text).matches()) {
            String number = text.strip().split("[\\s.]")[0];
            long depth = number.chars().filter(c -> c == '.').count();
            return (int) Math.min(4, 3 + depth);
        }

        // Tin hieu 2: co chu
        if (bodySize > 0 && line.fontSize() >= bodySize * 1.35) return 2;
        if (bodySize > 0 && line.fontSize() >= bodySize * 1.15 && line.bold()) return 3;

        // Dong ngan, IN HOA HET, khong ket thuc bang dau cau
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
