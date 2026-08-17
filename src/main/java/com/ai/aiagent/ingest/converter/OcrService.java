package com.ai.aiagent.ingest.converter;

import com.ai.aiagent.config.RagProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * OCR cho PDF ban scan: ket xuat tung trang thanh anh roi nho model thi giac doc.
 *
 * Vi sao khong dung Tesseract: no doi mot goi nhi phan cai san tren may chu (kem bo
 * du lieu tieng Viet), thu ma moi truong may chu noi bo cua ngan hang/CTCK thuong
 * khong cho cai them. Huong nay KHONG them phu thuoc nao: PDFBox da co san trong du
 * an va tu ket xuat duoc trang thanh anh, con model thi giac thi dung lai dung API
 * key da cau hinh. Doi lai, chat luong tieng Viet co dau cao hon Tesseract dang ke,
 * va bang bieu duoc giu lai duoi dang bang Markdown thay vi vo vun.
 *
 * CAI GIA phai tra: MOT loi goi model cho MOI trang. Vi vay mac dinh TAT
 * ({@code rag.ocr.enabled=false}) va co tran {@code rag.ocr.max-pages} - mot ban scan
 * 800 trang lot vao se lang le tieu het han muc API neu khong co tran nay.
 *
 * Goi REST truc tiep thay vi qua {@code LlmClient}: giao dien do chi nhan van ban,
 * mo rong no de mang anh se lam phuc tap ca bon provider trong khi chi mot cho nay
 * can. Cung ly do voi {@code GeminiLlmClient}.
 */
@Component
@Slf4j
public class OcrService {

    private static final String PROMPT = """
            Đây là ảnh chụp/scan MỘT TRANG của một văn bản hành chính tiếng Việt.
            Hãy chép lại TOÀN BỘ nội dung chữ trong trang thành Markdown.

            Quy tắc bắt buộc:
            - Chép đúng nguyên văn, giữ đầy đủ dấu tiếng Việt. KHÔNG tóm tắt, KHÔNG diễn giải.
            - Giữ cấu trúc: "Phần", "Chương", "Mục", "Điều", khoản, điểm thành heading/danh sách Markdown.
            - Bảng biểu chép lại thành bảng Markdown.
            - Con dấu, chữ ký, số văn bản: chép lại phần chữ đọc được.
            - Chỗ nào mờ không đọc được thì ghi [không đọc được], KHÔNG được đoán.
            - Chỉ trả về nội dung Markdown, không thêm lời dẫn nào của bạn.
            """;

    private final RagProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public OcrService(RagProperties props) {
        this.props = props;
    }

    public boolean isEnabled() {
        return props.getOcr().isEnabled();
    }

    /**
     * @return Markdown doc duoc, hoac chuoi rong khi OCR tat / that bai hoan toan
     */
    public String ocrPdf(byte[] pdfBytes, String fileName) {
        RagProperties.Ocr config = props.getOcr();
        if (!config.isEnabled()) return "";

        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            int total = document.getNumberOfPages();
            int pages = Math.min(total, Math.max(1, config.getMaxPages()));
            if (pages < total) {
                log.warn("OCR '{}': tai lieu {} trang, chi doc {} trang dau (rag.ocr.max-pages).",
                        fileName, total, pages);
            }
            log.info("OCR '{}': bat dau doc {} trang bang {}/{}.",
                    fileName, pages, config.getProvider(), config.getModel());

            List<String> rendered = render(document, pages, config, fileName);
            List<String> texts = readAll(rendered, config, fileName);

            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < texts.size(); i++) {
                String text = texts.get(i);
                if (text == null || text.isBlank()) continue;
                // Cung dinh dang moc trang voi PdfToMarkdown de truy nguon "trang may"
                // hoat dong giong nhau du tai lieu di duong nao.
                sb.append("\n<!-- page ").append(i + 1).append(" -->\n\n");
                sb.append(text.strip()).append("\n\n");
            }
            String markdown = sb.toString();
            if (markdown.isBlank()) {
                log.warn("OCR '{}': khong doc duoc chu nao.", fileName);
                return "";
            }
            log.info("OCR '{}': xong, {} ky tu.", fileName, markdown.length());
            return markdown;
        } catch (Exception e) {
            // OCR that bai KHONG duoc lam do ca luot nap: nguoi dung se thay canh bao
            // "PDF khong co text" nhu truoc khi co tinh nang nay.
            log.error("OCR '{}' that bai: {}", fileName, e.getMessage());
            return "";
        }
    }

    // ============================================================ Ket xuat anh

    private List<String> render(PDDocument document, int pages, RagProperties.Ocr config,
                                String fileName) {
        PDFRenderer renderer = new PDFRenderer(document);
        List<String> out = new ArrayList<>(pages);
        for (int i = 0; i < pages; i++) {
            try {
                // RGB chu khong phai ARGB: PNG co kenh alpha lam anh nang hon ma
                // khong them thong tin gi cho viec doc chu.
                BufferedImage image = renderer.renderImageWithDPI(
                        i, Math.max(72, config.getDpi()), ImageType.RGB);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                ImageIO.write(image, "png", buffer);
                out.add(Base64.getEncoder().encodeToString(buffer.toByteArray()));
            } catch (Exception e) {
                log.warn("OCR '{}': khong ket xuat duoc trang {}: {}", fileName, i + 1, e.getMessage());
                out.add(null);
            }
        }
        return out;
    }

    /** Doc song song nhung co gioi han - moi trang la mot loi goi model. */
    private List<String> readAll(List<String> images, RagProperties.Ocr config, String fileName) {
        int threads = Math.max(1, Math.min(config.getConcurrency(), 16));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "ocr");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Callable<String>> tasks = new ArrayList<>(images.size());
            for (int i = 0; i < images.size(); i++) {
                final String image = images.get(i);
                final int page = i + 1;
                tasks.add(() -> {
                    if (image == null) return "";
                    try {
                        return readPage(image, config);
                    } catch (Exception e) {
                        // Mot trang loi khong duoc lam mat ca tai lieu.
                        log.warn("OCR '{}': loi o trang {}: {}", fileName, page, e.getMessage());
                        return "";
                    }
                });
            }
            List<String> out = new ArrayList<>(images.size());
            for (Future<String> future : pool.invokeAll(tasks)) {
                out.add(future.get());
            }
            return out;
        } catch (Exception e) {
            log.error("OCR '{}': loi khi doc song song: {}", fileName, e.getMessage());
            return List.of();
        } finally {
            pool.shutdownNow();
        }
    }

    // ============================================================ Goi model

    private String readPage(String imageBase64, RagProperties.Ocr config) throws Exception {
        boolean anthropic = "ANTHROPIC".equalsIgnoreCase(config.getProvider());
        HttpRequest request = anthropic
                ? anthropicRequest(imageBase64, config)
                : openAiRequest(imageBase64, config);

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("OCR provider tra ve " + response.statusCode()
                    + ": " + trim(response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        return anthropic
                ? root.path("content").path(0).path("text").asText("")
                : root.path("choices").path(0).path("message").path("content").asText("");
    }

    private HttpRequest anthropicRequest(String imageBase64, RagProperties.Ocr config)
            throws Exception {
        ObjectNode source = mapper.createObjectNode();
        source.put("type", "base64");
        source.put("media_type", "image/png");
        source.put("data", imageBase64);

        ArrayNode content = mapper.createArrayNode();
        content.addObject().put("type", "image").set("source", source);
        content.addObject().put("type", "text").put("text", PROMPT);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("max_tokens", 8000);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").set("content", content);

        String apiKey = props.getAnthropic().getApiKey();
        requireKey(apiKey, "ANTHROPIC_API_KEY");
        return HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
    }

    private HttpRequest openAiRequest(String imageBase64, RagProperties.Ocr config)
            throws Exception {
        ObjectNode imageUrl = mapper.createObjectNode();
        imageUrl.put("url", "data:image/png;base64," + imageBase64);

        ArrayNode content = mapper.createArrayNode();
        content.addObject().put("type", "text").put("text", PROMPT);
        content.addObject().put("type", "image_url").set("image_url", imageUrl);

        ObjectNode body = mapper.createObjectNode();
        body.put("model", config.getModel());
        body.put("max_tokens", 8000);
        // Nhiet do 0: day la viec CHEP LAI, khong phai viec sang tao.
        body.put("temperature", 0.0);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").set("content", content);

        String apiKey = props.getOpenai().getApiKey();
        requireKey(apiKey, "OPENAI_API_KEY");
        String base = props.getOpenai().getBaseUrl();
        String url = (base == null || base.isBlank() ? "https://api.openai.com/v1" : base.strip())
                + "/chat/completions";
        return HttpRequest.newBuilder(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(config.getTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
    }

    private void requireKey(String apiKey, String envName) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Bat OCR (rag.ocr.enabled=true) nhung thieu "
                    + envName + ".");
        }
    }

    private static String trim(String value) {
        if (value == null) return "";
        return value.length() <= 300 ? value : value.substring(0, 300) + "…";
    }
}
