package com.ai.aiagent.llm;

import com.ai.aiagent.llm.LlmDtos.LlmRequest;
import com.ai.aiagent.llm.LlmDtos.LlmResponse;
import com.ai.aiagent.llm.LlmDtos.LlmUsage;
import com.ai.aiagent.llm.LlmDtos.StreamSink;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Google Gemini qua REST (Generative Language API) voi API key.
 *
 * Van goi REST truc tiep thay vi dung module cua langchain4j: ban 0.31 chi co
 * Vertex AI (can GCP credential), chua co Google AI Gemini dung API key.
 * Ban stream dung endpoint {@code :streamGenerateContent?alt=sse}.
 */
@Slf4j
public class GeminiLlmClient implements LlmClient {

    private static final String BASE =
            "https://generativelanguage.googleapis.com/v1beta/models/%s:%s?key=%s";

    private final String apiKey;
    private final String modelName;
    private final double temperature;
    private final int maxOutputTokens;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient;

    public GeminiLlmClient(String apiKey, String modelName, double temperature,
                           int maxOutputTokens, int timeoutSeconds) {
        this.apiKey = apiKey;
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxOutputTokens = maxOutputTokens;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.timeoutSeconds = timeoutSeconds;
    }

    private final int timeoutSeconds;

    @Override
    public LlmProvider provider() {
        return LlmProvider.GEMINI;
    }

    @Override
    public String model() {
        return modelName;
    }

    @Override
    public boolean supportsStreaming() {
        return true;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        long start = System.nanoTime();
        try {
            HttpRequest http = build(request, "generateContent", false);
            HttpResponse<String> res = httpClient.send(http, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                throw new IllegalStateException("Gemini tra ve loi " + res.statusCode() + ": " + res.body());
            }
            JsonNode root = mapper.readTree(res.body());
            String text = extractText(root);
            long ms = (System.nanoTime() - start) / 1_000_000;
            return new LlmResponse(text, usageOf(root, request, text), provider().name(), modelName, ms);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Loi khi goi Gemini: " + e.getMessage(), e);
        }
    }

    @Override
    public void stream(LlmRequest request, StreamSink sink) {
        long start = System.nanoTime();
        StringBuilder buffer = new StringBuilder();
        JsonNode[] lastChunk = new JsonNode[1];
        try {
            HttpRequest http = build(request, "streamGenerateContent", true);
            HttpResponse<java.io.InputStream> res =
                    httpClient.send(http, HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                sink.onError(new IllegalStateException("Gemini tra ve loi " + res.statusCode()));
                return;
            }
            try (BufferedReader reader = new BufferedReader(
                    new java.io.InputStreamReader(res.body(), java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) continue;
                    String payload = line.substring(5).trim();
                    if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
                    JsonNode chunk = mapper.readTree(payload);
                    lastChunk[0] = chunk;
                    String piece = extractText(chunk);
                    if (!piece.isEmpty()) {
                        buffer.append(piece);
                        sink.onToken(piece);
                    }
                }
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            String text = buffer.toString();
            sink.onComplete(new LlmResponse(text, usageOf(lastChunk[0], request, text),
                    provider().name(), modelName, ms));
        } catch (Exception e) {
            sink.onError(e);
        }
    }

    private HttpRequest build(LlmRequest request, String method, boolean sse) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.putArray("contents")
                .addObject()
                .put("role", "user")
                .putArray("parts")
                .addObject()
                .put("text", request.user());

        if (request.system() != null && !request.system().isBlank()) {
            body.putObject("system_instruction")
                    .putArray("parts")
                    .addObject()
                    .put("text", request.system());
        }
        ObjectNode gen = body.putObject("generationConfig");
        gen.put("temperature", temperature);
        gen.put("maxOutputTokens", request.maxOutputTokens() == null
                ? maxOutputTokens : request.maxOutputTokens());

        String url = String.format(BASE, modelName, method, apiKey);
        if (sse) url = url + "&alt=sse";

        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
    }

    private String extractText(JsonNode root) {
        if (root == null) return "";
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : parts) {
            JsonNode t = part.path("text");
            if (!t.isMissingNode()) sb.append(t.asText());
        }
        return sb.toString();
    }

    private LlmUsage usageOf(JsonNode root, LlmRequest request, String output) {
        int in = 0;
        int out = 0;
        if (root != null) {
            JsonNode meta = root.path("usageMetadata");
            in = meta.path("promptTokenCount").asInt(0);
            out = meta.path("candidatesTokenCount").asInt(0);
        }
        if (in == 0) {
            in = ModelPricing.estimateTokens(
                    OpenAiLlmClient.nullToEmpty(request.system()) + OpenAiLlmClient.nullToEmpty(request.user()));
        }
        if (out == 0) {
            out = ModelPricing.estimateTokens(output);
        }
        return ModelPricing.usage(provider(), modelName, in, out);
    }
}
