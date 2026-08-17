package com.ai.aiagent.ingest;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.ingest.converter.HtmlToMarkdown;
import com.ai.aiagent.ingest.converter.OcrService;
import com.ai.aiagent.ingest.converter.OfficeToMarkdown;
import com.ai.aiagent.ingest.converter.PdfToMarkdown;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kiem tra QUYET DINH co goi OCR hay khong.
 *
 * Day la cho dat tien nhat cua ca pipeline nap lieu - moi trang la mot loi goi model
 * thi giac - nen viec no chi chay dung luc can phai duoc bao ve bang test, khong phai
 * bang su can than cua nguoi doc code. Ban than chat luong OCR khong kiem tra o day
 * (do phu thuoc model); thu duoc kiem tra la LUAT KICH HOAT.
 */
class DocumentConverterOcrTest {

    private RagProperties props;
    private PdfToMarkdown pdf;
    private OcrService ocr;
    private DocumentConverterService converter;

    private static final byte[] FAKE_PDF = "%PDF-1.4 gia lap".getBytes();

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        pdf = mock(PdfToMarkdown.class);
        ocr = mock(OcrService.class);
        converter = new DocumentConverterService(props, mock(HtmlToMarkdown.class), pdf,
                mock(OfficeToMarkdown.class), ocr);
    }

    @Test
    @DisplayName("PDF co day du text thi KHONG goi OCR")
    void textPdfDoesNotTriggerOcr() {
        when(pdf.convert(any(), anyString())).thenReturn(longText(1));
        when(ocr.isEnabled()).thenReturn(true);

        DocumentConverterService.Result result = converter.convert(FAKE_PDF, "quy-che.pdf");

        verify(ocr, never()).ocrPdf(any(), anyString());
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    @DisplayName("PDF khong co text + OCR TAT: canh bao ro rang, khong am tham bo qua")
    void scannedPdfWithOcrOffWarns() {
        when(pdf.convert(any(), anyString())).thenReturn("");
        when(ocr.isEnabled()).thenReturn(false);

        DocumentConverterService.Result result = converter.convert(FAKE_PDF, "cong-van.pdf");

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.warnings())
                .as("nguoi nap file phai biet chinh xac phai bat cai gi")
                .anyMatch(w -> w.contains("rag.ocr.enabled"));
        verify(ocr, never()).ocrPdf(any(), anyString());
    }

    @Test
    @DisplayName("PDF khong co text + OCR BAT: dung ket qua OCR")
    void scannedPdfWithOcrOnUsesOcrResult() {
        when(pdf.convert(any(), anyString())).thenReturn("");
        when(ocr.isEnabled()).thenReturn(true);
        when(ocr.ocrPdf(any(), anyString())).thenReturn("# Điều 1\n\nNội dung đọc được từ bản scan.");

        DocumentConverterService.Result result = converter.convert(FAKE_PDF, "cong-van.pdf");

        assertThat(result.markdown()).contains("Nội dung đọc được từ bản scan");
        assertThat(result.warnings())
                .as("phai nhac nguoi dung kiem tra lai - OCR khong bao gio dung 100%")
                .anyMatch(w -> w.contains("OCR"));
    }

    @Test
    @DisplayName("PDF 'lai': co it text tren nhieu trang van phai OCR")
    void mostlyScannedPdfStillTriggersOcr() {
        // 10 trang nhung tong cong chi vai chuc ky tu -> trung binh moi trang rat thap.
        // Neu tinh theo TONG so ky tu thi truong hop nay lot luoi, va do la loai file
        // hay gap nhat: vai trang danh may + phan con lai la ban scan dinh kem.
        StringBuilder thin = new StringBuilder();
        for (int page = 1; page <= 10; page++) {
            thin.append("<!-- page ").append(page).append(" -->\n\nBIDV\n\n");
        }
        when(pdf.convert(any(), anyString())).thenReturn(thin.toString());
        when(ocr.isEnabled()).thenReturn(true);
        when(ocr.ocrPdf(any(), anyString())).thenReturn("# Quyết định\n\nToàn văn.");

        DocumentConverterService.Result result = converter.convert(FAKE_PDF, "quyet-dinh.pdf");

        verify(ocr).ocrPdf(any(), anyString());
        assertThat(result.markdown()).contains("Toàn văn");
    }

    @Test
    @DisplayName("Dat min-chars-per-page=0 thi chi OCR khi khong co chu nao")
    void thresholdZeroOnlyOcrsCompletelyEmptyPdfs() {
        props.getOcr().setMinCharsPerPage(0);
        StringBuilder thin = new StringBuilder();
        for (int page = 1; page <= 10; page++) {
            thin.append("<!-- page ").append(page).append(" -->\n\nBIDV\n\n");
        }
        when(pdf.convert(any(), anyString())).thenReturn(thin.toString());
        when(ocr.isEnabled()).thenReturn(true);

        converter.convert(FAKE_PDF, "quyet-dinh.pdf");

        verify(ocr, never()).ocrPdf(any(), anyString());
    }

    @Test
    @DisplayName("OCR that bai KHONG lam do luot nap - chi canh bao")
    void failedOcrDoesNotThrow() {
        when(pdf.convert(any(), anyString())).thenReturn("");
        when(ocr.isEnabled()).thenReturn(true);
        when(ocr.ocrPdf(any(), anyString())).thenReturn("");

        DocumentConverterService.Result result = converter.convert(FAKE_PDF, "cong-van.pdf");

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.warnings()).anyMatch(w -> w.contains("OCR khong doc duoc"));
    }

    /** Doan van du dai de vuot nguong min-chars-per-page. */
    private String longText(int pages) {
        StringBuilder sb = new StringBuilder();
        for (int page = 1; page <= pages; page++) {
            sb.append("<!-- page ").append(page).append(" -->\n\n");
            sb.append("Điều 1. Phạm vi điều chỉnh. Quy chế này quy định về trình tự, thủ tục ")
              .append("thực hiện nghiệp vụ môi giới chứng khoán tại Công ty.\n\n");
        }
        return sb.toString();
    }
}
