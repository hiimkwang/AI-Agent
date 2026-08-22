package com.ai.aiagent.ingest;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.ingest.converter.HtmlToMarkdown;
import com.ai.aiagent.ingest.converter.OcrService;
import com.ai.aiagent.ingest.converter.OfficeToMarkdown;
import com.ai.aiagent.ingest.converter.PdfToMarkdown;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class DocumentConverterService {

    public record Result(String markdown, DocumentFormat format, List<String> warnings) {
        public boolean isEmpty() {
            return markdown == null || markdown.isBlank();
        }
    }

    private final RagProperties props;
    private final HtmlToMarkdown htmlConverter;
    private final PdfToMarkdown pdfConverter;
    private final OfficeToMarkdown officeConverter;
    private final OcrService ocr;

    public DocumentConverterService(RagProperties props,
                                    HtmlToMarkdown htmlConverter,
                                    PdfToMarkdown pdfConverter,
                                    OfficeToMarkdown officeConverter,
                                    OcrService ocr) {
        this.props = props;
        this.htmlConverter = htmlConverter;
        this.pdfConverter = pdfConverter;
        this.officeConverter = officeConverter;
        this.ocr = ocr;
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
                yield fromPdf(bytes, fileName, warnings);
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
        log.debug("Converted '{}' ({}) to {} chars of Markdown", fileName, format, markdown.length());
        return new Result(markdown, format, warnings);
    }

    private String fromPdf(byte[] bytes, String fileName, List<String> warnings) {
        String md = pdfConverter.convert(bytes, fileName);
        if (!needsOcr(md)) return md;

        if (!ocr.isEnabled()) {
            warnings.add("PDF khong co text (co the la ban scan) - bat rag.ocr.enabled=true "
                    + "hoac OCR truoc khi nap.");
            return md;
        }

        String scanned = ocr.ocrPdf(bytes, fileName);
        if (scanned.isBlank()) {
            warnings.add("PDF khong co text va OCR khong doc duoc noi dung nao.");
            return md;
        }
        warnings.add("Noi dung PDF nay duoc doc bang OCR - nen kiem tra lai ban Markdown "
                + "o man Tai lieu truoc khi tin dung.");
        return scanned;
    }

    private boolean needsOcr(String markdown) {
        if (markdown == null || markdown.isBlank()) return true;

        int minPerPage = props.getOcr().getMinCharsPerPage();
        if (minPerPage <= 0) return false;

        int pages = 0;
        int from = 0;
        while ((from = markdown.indexOf("<!-- page ", from)) >= 0) {
            pages++;
            from += 10;
        }
        if (pages == 0) pages = 1;

        String textOnly = markdown.replaceAll("<!-- page \\d+ -->", "").strip();
        return textOnly.length() / pages < minPerPage;
    }

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
