package com.ai.aiagent.modules.rag.rerank;

import com.ai.aiagent.modules.rag.store.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Rerank bằng Cohere Rerank API (cross-encoder chuyên dụng, đa ngôn ngữ).
 * Chính xác hơn LLM rerank và nhanh hơn. Chỉ hoạt động khi có COHERE_API_KEY.
 * Việc chọn dùng bộ nào do {@link RerankerProvider} quyết định theo cấu hình.
 */
@Component
@Slf4j
public class CohereReranker implements Reranker {

    private static final String ENDPOINT = "https://api.cohere.com/v2/rerank";

    @Value("${rag.cohere.api-key}")
    private String apiKey;
    @Value("${rag.cohere.rerank-model}")
    private String model;

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public List<RetrievedChunk> rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates.isEmpty()) return List.of();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("query", query);
            body.put("top_n", Math.min(topK, candidates.size()));
            ArrayNode docs = body.putArray("documents");
            for (RetrievedChunk c : candidates) {
                docs.add(c.searchableText());
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new RuntimeException("Cohere rerank lỗi " + resp.statusCode() + ": " + resp.body());
            }

            JsonNode results = mapper.readTree(resp.body()).path("results");
            List<RetrievedChunk> reranked = new ArrayList<>();
            for (JsonNode r : results) {
                int idx = r.path("index").asInt(-1);
                if (idx >= 0 && idx < candidates.size()) {
                    RetrievedChunk chunk = candidates.get(idx);
                    chunk.setRawScore(r.path("relevance_score").asDouble());
                    reranked.add(chunk);
                }
            }
            log.info("Cohere rerank: {} ứng viên -> giữ {} đoạn.", candidates.size(), reranked.size());
            return reranked;
        } catch (Exception e) {
            log.warn("Cohere rerank lỗi ({}) -> fallback giữ thứ tự gốc.", e.getMessage());
            return candidates.subList(0, Math.min(topK, candidates.size()));
        }
    }
}
