package com.ai.aiagent.rerank;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.store.StoreModels.RetrievedChunk;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class CohereReranker implements Reranker {

    private static final String ENDPOINT = "https://api.cohere.com/v2/rerank";
    private static final int MAX_DOC_CHARS = 4000;

    private final RagProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    public CohereReranker(RagProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "COHERE";
    }

    public boolean isAvailable() {
        String key = props.getCohere().getApiKey();
        return key != null && !key.isBlank();
    }

    @Override
    public RerankResult rerank(String query, List<RetrievedChunk> candidates, int topK) {
        if (candidates.isEmpty()) {
            return RerankResult.reliable(List.of(), name());
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.getCohere().getRerankModel());
            body.put("query", query);
            body.put("top_n", Math.min(topK, candidates.size()));
            ArrayNode docs = body.putArray("documents");
            for (RetrievedChunk c : candidates) {
                String text = c.rerankText();
                docs.add(text.length() > MAX_DOC_CHARS ? text.substring(0, MAX_DOC_CHARS) : text);
            }

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + props.getCohere().getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Cohere rerank returned HTTP {}, marking the result unreliable. {}",
                        response.statusCode(), abbreviate(response.body()));
                return RerankResult.degraded(fallback(candidates, topK), name());
            }

            JsonNode results = mapper.readTree(response.body()).path("results");
            List<RetrievedChunk> out = new ArrayList<>();
            for (JsonNode r : results) {
                int index = r.path("index").asInt(-1);
                if (index < 0 || index >= candidates.size()) continue;
                RetrievedChunk chunk = candidates.get(index);
                chunk.setRerankScore(r.path("relevance_score").asDouble(0));
                out.add(chunk);
            }
            log.debug("Cohere rerank: {} candidates in, {} passages kept.", candidates.size(), out.size());
            return RerankResult.reliable(out, name());

        } catch (Exception e) {
            log.warn("Cohere rerank failed ({}), marking the result unreliable.", e.getMessage());
            return RerankResult.degraded(fallback(candidates, topK), name());
        }
    }

    private List<RetrievedChunk> fallback(List<RetrievedChunk> candidates, int topK) {
        List<RetrievedChunk> out = new ArrayList<>(
                candidates.subList(0, Math.min(topK, candidates.size())));
        out.forEach(c -> c.setRerankScore(-1));
        return out;
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200);
    }
}
