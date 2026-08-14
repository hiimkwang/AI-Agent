package com.ai.aiagent.llm;

/**
 * Cac kieu du lieu dung chung cho tang LLM. Gom vao 1 file cho gon.
 */
public final class LlmDtos {

    private LlmDtos() {
    }

    /**
     * Mot yeu cau sinh van ban.
     *
     * @param system         chi thi he thong (co the null)
     * @param user           noi dung nguoi dung
     * @param maxOutputTokens null = dung mac dinh cua cau hinh
     */
    public record LlmRequest(String system, String user, Integer maxOutputTokens) {

        public static LlmRequest of(String user) {
            return new LlmRequest(null, user, null);
        }

        public static LlmRequest of(String system, String user) {
            return new LlmRequest(system, user, null);
        }
    }

    /**
     * So token va chi phi cua mot lan goi.
     *
     * @param costUsd chi phi UOC TINH theo bang gia niem yet trong {@link ModelPricing};
     *                khong phai so tien thuc tren hoa don (co the co chiet khau, cache...).
     */
    public record LlmUsage(int inputTokens, int outputTokens, double costUsd) {

        public static final LlmUsage EMPTY = new LlmUsage(0, 0, 0.0);

        public LlmUsage plus(LlmUsage other) {
            if (other == null) return this;
            return new LlmUsage(
                    inputTokens + other.inputTokens,
                    outputTokens + other.outputTokens,
                    costUsd + other.costUsd);
        }

        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    public record LlmResponse(String text, LlmUsage usage, String provider, String model, long latencyMs) {
    }

    /** Nhan token khi stream. */
    public interface StreamSink {
        void onToken(String token);

        void onComplete(LlmResponse response);

        void onError(Throwable error);
    }
}
