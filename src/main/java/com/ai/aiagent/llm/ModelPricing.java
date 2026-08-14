package com.ai.aiagent.llm;

import com.ai.aiagent.llm.LlmDtos.LlmUsage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bang gia niem yet (USD / 1 trieu token) de UOC TINH chi phi moi cau tra loi.
 *
 * Muc dich la co con so hien len tren UI va luu vao {@code rag_messages.cost_usd},
 * nho vay biet cau hoi nao dat, model nao dang tieu tien - truoc day khong do gi ca.
 * Day KHONG phai so tien tren hoa don (chua tinh chiet khau, prompt cache, batch).
 *
 * Khop theo tien to dai nhat, nen "gpt-4o-mini-2024-07-18" van khop "gpt-4o-mini".
 */
public final class ModelPricing {

    private record Price(double inputPerMillion, double outputPerMillion) {
    }

    private static final Map<String, Price> TABLE = new LinkedHashMap<>();

    static {
        // --- Anthropic / Claude ---
        TABLE.put("claude-fable-5", new Price(10.00, 50.00));
        TABLE.put("claude-mythos-5", new Price(10.00, 50.00));
        TABLE.put("claude-opus-5", new Price(5.00, 25.00));
        TABLE.put("claude-opus-4-8", new Price(5.00, 25.00));
        TABLE.put("claude-opus-4-7", new Price(5.00, 25.00));
        TABLE.put("claude-opus-4-6", new Price(5.00, 25.00));
        TABLE.put("claude-sonnet-5", new Price(3.00, 15.00));
        TABLE.put("claude-sonnet-4-6", new Price(3.00, 15.00));
        TABLE.put("claude-haiku-4-5", new Price(1.00, 5.00));

        // --- OpenAI ---
        TABLE.put("gpt-4o-mini", new Price(0.15, 0.60));
        TABLE.put("gpt-4o", new Price(2.50, 10.00));
        TABLE.put("gpt-4.1-mini", new Price(0.40, 1.60));
        TABLE.put("gpt-4.1-nano", new Price(0.10, 0.40));
        TABLE.put("gpt-4.1", new Price(2.00, 8.00));
        TABLE.put("text-embedding-3-small", new Price(0.02, 0.0));
        TABLE.put("text-embedding-3-large", new Price(0.13, 0.0));

        // --- Google Gemini ---
        TABLE.put("gemini-2.0-flash", new Price(0.10, 0.40));
        TABLE.put("gemini-1.5-flash", new Price(0.075, 0.30));
        TABLE.put("gemini-1.5-pro", new Price(1.25, 5.00));

        // --- Cohere rerank: tinh theo lan goi, khong theo token ---
        TABLE.put("rerank-", new Price(0.0, 0.0));
    }

    private ModelPricing() {
    }

    /** Model chay local (Ollama) => chi phi 0. */
    public static double estimateUsd(LlmProvider provider, String model, int inputTokens, int outputTokens) {
        if (provider == LlmProvider.OLLAMA) return 0.0;
        Price p = lookup(model);
        if (p == null) return 0.0;
        return inputTokens / 1_000_000.0 * p.inputPerMillion()
                + outputTokens / 1_000_000.0 * p.outputPerMillion();
    }

    public static LlmUsage usage(LlmProvider provider, String model, int inputTokens, int outputTokens) {
        return new LlmUsage(inputTokens, outputTokens,
                estimateUsd(provider, model, inputTokens, outputTokens));
    }

    public static boolean isPriced(String model) {
        return lookup(model) != null;
    }

    private static Price lookup(String model) {
        if (model == null) return null;
        String m = model.toLowerCase();
        Price best = null;
        int bestLen = -1;
        for (Map.Entry<String, Price> e : TABLE.entrySet()) {
            if (m.startsWith(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue();
                bestLen = e.getKey().length();
            }
        }
        return best;
    }

    /**
     * Uoc luong so token khi provider khong tra ve usage (vd Gemini REST, hoac
     * stream bi ngat giua duong). ~4 ky tu / token voi van ban Latin; tieng Viet
     * co dau ton nhieu hon nen dung 3.
     */
    public static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return Math.max(1, text.length() / 3);
    }
}
