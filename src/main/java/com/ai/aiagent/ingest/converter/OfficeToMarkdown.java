package com.ai.aiagent.ingest.converter;

import com.ai.aiagent.ingest.DocumentFormat;
import com.ai.aiagent.ingest.Markdown;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hslf.usermodel.HSLFSlide;
import org.apache.poi.hslf.usermodel.HSLFSlideShow;
import org.apache.poi.hslf.usermodel.HSLFTextParagraph;
import org.apache.poi.hslf.usermodel.HSLFTextShape;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Office -> Markdown, GIU CAU TRUC.
 *
 * Diem khac quan trong so voi cach cu (ApachePoiDocumentParser cua langchain4j):
 * cach cu tra ve text phang, lam mat het heading va bang. Bo chuyen doi nay:
 *   - Word:  style "Heading N" -> "#" tuong ung; bang -> bang Markdown; giu dung
 *            THU TU xuat hien cua doan va bang trong tai lieu.
 *   - Excel: moi sheet -> mot heading + mot bang Markdown (dung DataFormatter nen
 *            ngay/tien/phan tram hien dung nhu trong file).
 *   - PowerPoint: moi slide -> mot heading + noi dung + bang.
 */
@Component
@Slf4j
public class OfficeToMarkdown {

    private final DataFormatter dataFormatter = new DataFormatter();

    public String convert(byte[] bytes, String fileName, DocumentFormat format) {
        try {
            String markdown = switch (format) {
                case DOCX -> fromDocx(bytes, fileName);
                case DOC -> fromDoc(bytes, fileName);
                case XLSX, XLS -> fromSpreadsheet(bytes, fileName);
                case PPTX -> fromPptx(bytes, fileName);
                case PPT -> fromPpt(bytes, fileName);
                default -> throw new IllegalArgumentException(
                        "Khong phai file Office: " + format);
            };
            return Markdown.normalize(markdown);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Khong doc duoc file Office '" + fileName + "': " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ Word

    private String fromDocx(byte[] bytes, String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(Markdown.heading(1, stripExtension(fileName)));

        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            // getBodyElements() giu dung thu tu doan/bang trong tai lieu
            for (IBodyElement element : doc.getBodyElements()) {
                if (element instanceof XWPFParagraph p) {
                    appendParagraph(sb, p);
                } else if (element instanceof XWPFTable t) {
                    sb.append('\n').append(docxTable(t)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private void appendParagraph(StringBuilder sb, XWPFParagraph p) {
        String text = p.getText();
        if (text == null || text.isBlank()) return;
        text = text.strip();

        int heading = headingLevelOf(p);
        if (heading > 0) {
            sb.append(Markdown.heading(Math.min(6, heading + 1), text));
            return;
        }
        if (p.getNumID() != null) {
            sb.append("- ").append(text).append('\n');
            return;
        }
        sb.append(text).append("\n\n");
    }

    /** @return 0 neu khong phai heading, 1..6 theo style "Heading N" / "Title". */
    private int headingLevelOf(XWPFParagraph p) {
        String style = p.getStyle();
        if (style == null) style = p.getStyleID();
        if (style == null) return 0;
        String s = style.toLowerCase().replace(" ", "");
        if (s.startsWith("title")) return 1;
        if (s.startsWith("heading") || s.startsWith("dau") || s.startsWith("tieude")) {
            String digits = s.replaceAll("\\D+", "");
            if (digits.isEmpty()) return 1;
            try {
                return Math.max(1, Math.min(6, Integer.parseInt(digits)));
            } catch (NumberFormatException e) {
                return 1;
            }
        }
        return 0;
    }

    private String docxTable(XWPFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XWPFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XWPFTableCell cell : row.getTableCells()) {
                cells.add(cell.getText());
            }
            if (cells.stream().anyMatch(c -> c != null && !c.isBlank())) {
                rows.add(cells);
            }
        }
        return Markdown.table(rows);
    }

    private String fromDoc(byte[] bytes, String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(Markdown.heading(1, stripExtension(fileName)));
        // .doc (binary cu) khong cho truy cap style dang tin cay -> lay theo doan
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes));
             WordExtractor extractor = new WordExtractor(doc)) {
            for (String paragraph : extractor.getParagraphText()) {
                String text = paragraph == null ? "" : paragraph.strip();
                if (text.isEmpty()) continue;
                // Dong ngan IN HOA => coi la heading
                if (text.length() <= 80 && text.equals(text.toUpperCase()) && text.matches(".*\\p{L}.*")) {
                    sb.append(Markdown.heading(2, text));
                } else {
                    sb.append(text).append("\n\n");
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------- Excel

    private String fromSpreadsheet(byte[] bytes, String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(Markdown.heading(1, stripExtension(fileName)));

        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet.getPhysicalNumberOfRows() == 0) continue;

                sb.append(Markdown.heading(2, "Sheet: " + sheet.getSheetName()));

                List<List<String>> rows = new ArrayList<>();
                int lastRow = sheet.getLastRowNum();
                int width = 0;
                for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;
                    width = Math.max(width, row.getLastCellNum());
                }
                if (width <= 0) continue;

                for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
                    Row row = sheet.getRow(r);
                    List<String> cells = new ArrayList<>(width);
                    boolean hasValue = false;
                    for (int c = 0; c < width; c++) {
                        String value = "";
                        if (row != null) {
                            Cell cell = row.getCell(c);
                            if (cell != null) {
                                value = cellValue(cell);
                                if (!value.isBlank()) hasValue = true;
                            }
                        }
                        cells.add(value);
                    }
                    if (hasValue) rows.add(cells);
                }
                sb.append(Markdown.table(rows)).append('\n');
            }
        }
        return sb.toString();
    }

    /** DataFormatter tra ve dung dinh dang nhu nguoi dung thay (ngay, tien, %). */
    private String cellValue(Cell cell) {
        try {
            return dataFormatter.formatCellValue(cell).strip();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // -------------------------------------------------------- PowerPoint

    private String fromPptx(byte[] bytes, String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(Markdown.heading(1, stripExtension(fileName)));

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(bytes))) {
            int index = 0;
            for (XSLFSlide slide : show.getSlides()) {
                index++;
                String title = slide.getTitle();
                sb.append(Markdown.heading(2, "Slide " + index
                        + (title == null || title.isBlank() ? "" : ": " + title.strip())));

                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTable table) {
                        sb.append('\n').append(pptxTable(table)).append('\n');
                    } else if (shape instanceof XSLFTextShape textShape) {
                        String text = textShape.getText();
                        if (text == null || text.isBlank()) continue;
                        if (title != null && text.strip().equals(title.strip())) continue;
                        for (String line : text.split("\\r?\\n")) {
                            if (!line.isBlank()) sb.append("- ").append(line.strip()).append('\n');
                        }
                        sb.append('\n');
                    }
                }
                // Ghi chu cua nguoi trinh bay thuong chua giai thich quan trong
                if (slide.getNotes() != null) {
                    StringBuilder notes = new StringBuilder();
                    for (XSLFShape shape : slide.getNotes().getShapes()) {
                        if (shape instanceof XSLFTextShape ts && ts.getText() != null
                                && !ts.getText().isBlank()) {
                            notes.append(ts.getText().strip()).append(' ');
                        }
                    }
                    if (notes.length() > 0) {
                        sb.append("> Ghi chu: ").append(notes.toString().strip()).append("\n\n");
                    }
                }
            }
        }
        return sb.toString();
    }

    private String pptxTable(XSLFTable table) {
        List<List<String>> rows = new ArrayList<>();
        for (XSLFTableRow row : table.getRows()) {
            List<String> cells = new ArrayList<>();
            for (XSLFTableCell cell : row.getCells()) {
                cells.add(cell.getText());
            }
            if (cells.stream().anyMatch(c -> c != null && !c.isBlank())) rows.add(cells);
        }
        return Markdown.table(rows);
    }

    private String fromPpt(byte[] bytes, String fileName) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append(Markdown.heading(1, stripExtension(fileName)));

        try (HSLFSlideShow show = new HSLFSlideShow(new ByteArrayInputStream(bytes))) {
            int index = 0;
            for (HSLFSlide slide : show.getSlides()) {
                index++;
                sb.append(Markdown.heading(2, "Slide " + index));
                for (List<HSLFTextParagraph> group : slide.getTextParagraphs()) {
                    for (HSLFTextParagraph paragraph : group) {
                        String text = HSLFTextParagraph.getText(java.util.List.of(paragraph));
                        if (text != null && !text.isBlank()) {
                            sb.append("- ").append(text.strip()).append('\n');
                        }
                    }
                }
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private String hslfShapeText(HSLFTextShape shape) {
        return shape.getText();
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
