package com.ai.aiagent.modules.rag.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * Triển khai {@link ChatLanguageModel} cho Google Gemini bằng cách gọi trực tiếp
 * REST API (Generative Language API) với API key.
 *
 * Lý do tự viết REST thay vì dùng module langchain4j: phiên bản langchain4j 0.31
 * chưa có module Google AI Gemini dùng API key (chỉ có Vertex AI cần GCP credential).
 * Gọi REST là cách gọn nhẹ và không phụ thuộc thêm dependency.
 */
public class GeminiChatModel implements ChatLanguageModel {

    private static final String ENDPOINT_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s";

    private final String apiKey;
    private final String modelName;
    private final double temperature;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    public GeminiChatModel(String apiKey, String modelName, double temperature) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.temperature = temperature;
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        try {
            ObjectNode body = mapper.createObjectNode();

            // Gom các SystemMessage thành system_instruction
            StringBuilder systemText = new StringBuilder();
            ArrayNode contents = body.putArray("contents");

            for (ChatMessage message : messages) {
                if (message instanceof SystemMessage sm) {
                    if (systemText.length() > 0) systemText.append("\n");
                    systemText.append(sm.text());
                } else if (message instanceof UserMessage um) {
                    addContent(contents, "user", textOf(um));
                } else if (message instanceof AiMessage am) {
                    addContent(contents, "model", am.text());
                }
            }

            if (systemText.length() > 0) {
                ObjectNode sysInstruction = body.putObject("system_instruction");
                sysInstruction.putArray("parts").addObject().put("text", systemText.toString());
            }

            ObjectNode genConfig = body.putObject("generationConfig");
            genConfig.put("temperature", temperature);

            String url = String.format(ENDPOINT_TEMPLATE, modelName, apiKey);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> httpResponse =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (httpResponse.statusCode() != 200) {
                throw new RuntimeException("Gemini API trả về lỗi " + httpResponse.statusCode()
                        + ": " + httpResponse.body());
            }

            JsonNode root = mapper.readTree(httpResponse.body());
            JsonNode textNode = root.path("candidates").path(0)
                    .path("content").path("parts").path(0).path("text");

            String answer = textNode.isMissingNode() ? "" : textNode.asText();
            return Response.from(AiMessage.from(answer));

        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Lỗi khi gọi Gemini API: " + e.getMessage(), e);
        }
    }

    private void addContent(ArrayNode contents, String role, String text) {
        ObjectNode content = contents.addObject();
        content.put("role", role);
        content.putArray("parts").addObject().put("text", text);
    }

    /** Lấy phần text từ UserMessage một cách an toàn theo nhiều phiên bản API. */
    private String textOf(UserMessage um) {
        try {
            return um.singleText();
        } catch (Exception e) {
            return um.contents().toString();
        }
    }
}
