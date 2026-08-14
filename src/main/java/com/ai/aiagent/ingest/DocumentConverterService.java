package com.ai.aiagent.ingest;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.ingest.converter.HtmlToMarkdown;
import com.ai.aiagent.ingest.converter.OfficeToMarkdown;
import com.ai.aiagent.ingest.converter.PdfToMarkdown;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Diem vao duy nhat de chuyen MOI dinh dang -> Markdown.
 *
 * Toan bo pipeline nap lieu di qua day truoc khi bam chunk, nen chi co MOT dinh
 * dang trung gian phai xu ly (Markdown). Loi ich:
 *   - cau truc heading duoc giu lai -> bam chunk theo muc, khong cat ngang dieu/khoan
 *   - bang bieu duoc giu duoi dang bang Markdown
 *   - ban Markdown duoc luu lai nen bam lai chunk khong can convert lai file goc
 *   - ban co the tu chuyen sang .md truoc roi nap: he thong nhan .md nguyen ban
 */
@Service
@Slf4j
public class DocumentConverterService {

    /**
     * @param markdown  ket qua Markdown
     * @param format    dinh dang nguon
     * @param warnings  canh bao (vd PDF khong co text -> can OCR)
     */
    public record Result(String markdown, DocumentFormat format, List<String> warnings) {
        public boolean isEmpty() {
            return markdown == null || markdown.isBlank();
        }
    }

    private final RagProperties props;
    private final HtmlToMarkdown htmlConverter;
    private final PdfToMarkdown pdfConverter;
    private final OfficeToMarkdown officeConverter;

    public DocumentConverterService(RagProperties props,
                                    HtmlToMarkdown htmlConverter,
                                    PdfToMarkdown pdfConverter,
                                    OfficeToMarkdown officeConverter) {
        this.props = props;
        this.htmlConverter = htmlConverter;
        this.pdfConverter = pdfConverter;
        this.officeConverter = officeConverter;
    }

    public Result convert(byte[] bytes, String fileName) {
        DocumentFormat format = DocumentFormat.fromFileName(fileName);
        if (format == null) {
            throw new IllegalArgumentException("Dinh dang khong duoc ho tro: '" + fileName
                    + "'. Cac duoi file duoc ho tro: " + DocumentFormat.allExtensions());
        }
        List<String> warnings = new ArrayList<>();
        String markdown = switch (format) {
            case MARKDOWN -> Markdown.normalize(new String(bytes, StandardCharsets.UTF_8));
            case TEXT -> fromPlainText(bytes, fileName);
            case HTML -> {
                requireEnabled(props.getConvert().isHtmlEnabled(), "HTML", fileName);
                yield htmlConverter.convert(bytes, fileName, null);
            }
            case PDF -> {
                requireEnabled(props.getConvert().isPdfEnabled(), "PDF", fileName);
                String md = pdfConverter.convert(bytes, fileName);
                if (md.isBlank()) {
                    warnings.add("PDF khong chua text (co the la ban scan) - can OCR truoc khi nap.");
                }
                yield md;
            }
            case DOCX, DOC, XLSX, XLS, PPTX, PPT -> {
                requireEnabled(props.getConvert().isOfficeEnabled(), "Office", fileName);
                yield officeConverter.convert(bytes, fileName, format);
            }
        };

        int max = props.getConvert().getMaxMarkdownChars();
        if (markdown.length() > max) {
            warnings.add("Tai lieu dai " + markdown.length() + " ky tu, da cat con " + max + ".");
            markdown = Markdown.truncate(markdown, max);
        }
        log.debug("Chuyen doi '{}' ({}) -> {} ky tu Markdown", fileName, format, markdown.length());
        return new Result(markdown, format, warnings);
    }

    /** File .txt/.csv: bao heading tu ten file; CSV duoc chuyen thanh bang Markdown. */
    private String fromPlainText(byte[] bytes, String fileName) {
        String raw = new String(bytes, StandardCharsets.UTF_8);
        String ext = DocumentFormat.extensionOf(fileName);
        StringBuilder sb = new StringBuilder();
        sb.append(Markdown.heading(1, stripExtension(fileName)));

        if ("csv".equals(ext) || "tsv".equals(ext)) {
            String delimiter = "tsv".equals(ext) ? "\t" : ",";
            List<List<String>> rows = new ArrayList<>();
            for (String line : raw.split("\\r?\\n")) {
                if (line.isBlank()) continue;
                rows.add(List.of(line.split(java.util.regex.Pattern.quote(delimiter), -1)));
                if (rows.size() > 5000) break;
            }
            sb.append(Markdown.table(rows));
        } else {
            sb.append(raw);
        }
        return Markdown.normalize(sb.toString());
    }

    private void requireEnabled(boolean enabled, String what, String fileName) {
        if (!enabled) {
            throw new IllegalStateException("Chuyen doi " + what + " dang bi tat trong cau hinh"
                    + " (rag.convert.*) nen khong nap duoc '" + fileName + "'.");
        }
    }

    private String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
