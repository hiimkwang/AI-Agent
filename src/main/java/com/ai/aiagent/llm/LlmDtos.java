package com.ai.aiagent.llm;

public final class LlmDtos {

    private LlmDtos() {
    }

    public record LlmRequest(String system, String user, Integer maxOutputTokens) {

        public static LlmRequest of(String user) {
            return new LlmRequest(null, user, null);
        }

        public static LlmRequest of(String system, String user) {
            return new LlmRequest(system, user, null);
        }
    }

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

    public interface StreamSink {
        void onToken(String token);

        void onComplete(LlmResponse response);

        void onError(Throwable error);

        /**
         * True khi nguoi dung da bam "dung". Client streaming phai kiem tra giua cac
         * token va thoat vong lap, de khong tra tien cho phan sinh ra khong ai doc.
         */
        default boolean cancelled() {
            return false;
        }
    }
}
