package com.ai.aiagent.llm;

import com.ai.aiagent.llm.LlmDtos.LlmRequest;
import com.ai.aiagent.llm.LlmDtos.LlmResponse;
import com.ai.aiagent.llm.LlmDtos.StreamSink;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ThinkingConfigAdaptive;
import com.anthropic.models.messages.ThinkingConfigDisabled;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
public class AnthropicLlmClient implements LlmClient {

    private final AnthropicClient client;
    private final String modelName;
    private final int maxOutputTokens;
    private final boolean thinkingEnabled;
    private final OutputConfig.Effort effort;

    public AnthropicLlmClient(String apiKey, String modelName, int maxOutputTokens,
                              boolean thinkingEnabled, String effortName, int timeoutSeconds) {
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(apiKey)
                .timeout(Duration.ofSeconds(Math.max(timeoutSeconds, 60)))
                .build();
        this.modelName = modelName;
        this.maxOutputTokens = maxOutputTokens;
        this.thinkingEnabled = thinkingEnabled;
        this.effort = resolveEffort(effortName, thinkingEnabled);
    }

    private static OutputConfig.Effort resolveEffort(String name, boolean thinkingEnabled) {
        String v = name == null ? "low" : name.trim().toLowerCase();
        OutputConfig.Effort requested = switch (v) {
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.LOW;
        };
        // Claude rejects temperature and enables thinking by default; thinking can only
        // be turned off when effort is high or below, so drop the effort instead.
        if (!thinkingEnabled
                && (requested.equals(OutputConfig.Effort.XHIGH) || requested.equals(OutputConfig.Effort.MAX))) {
            log.warn("Claude cannot disable thinking at effort={}, lowering it to high.", v);
            return OutputConfig.Effort.HIGH;
        }
        return requested;
    }

    @Override
    public LlmProvider provider() {
        return LlmProvider.ANTHROPIC;
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
        Message message = client.messages().create(params(request));
        long ms = (System.nanoTime() - start) / 1_000_000;

        StringBuilder text = new StringBuilder();
        for (ContentBlock block : message.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
        }
        int in = (int) message.usage().inputTokens();
        int out = (int) message.usage().outputTokens();
        return new LlmResponse(text.toString(), ModelPricing.usage(provider(), modelName, in, out),
                provider().name(), modelName, ms);
    }

    @Override
    public void stream(LlmRequest request, StreamSink sink) {
        long start = System.nanoTime();
        StringBuilder buffer = new StringBuilder();
        try (StreamResponse<RawMessageStreamEvent> response =
                     client.messages().createStreaming(params(request))) {
            // takeWhile thoat som khi bi dung; try-with-resources dong luon ket noi HTTP.
            response.stream()
                    .takeWhile(event -> !sink.cancelled())
                    .forEach(event -> event.contentBlockDelta()
                            .flatMap(delta -> delta.delta().text())
                            .ifPresent(textDelta -> {
                                buffer.append(textDelta.text());
                                sink.onToken(textDelta.text());
                            }));
            long ms = (System.nanoTime() - start) / 1_000_000;
            String text = buffer.toString();
            int in = ModelPricing.estimateTokens(
                    OpenAiLlmClient.nullToEmpty(request.system()) + OpenAiLlmClient.nullToEmpty(request.user()));
            int out = ModelPricing.estimateTokens(text);
            sink.onComplete(new LlmResponse(text, ModelPricing.usage(provider(), modelName, in, out),
                    provider().name(), modelName, ms));
        } catch (Exception e) {
            sink.onError(e);
        }
    }

    private MessageCreateParams params(LlmRequest request) {
        MessageCreateParams.Builder builder = MessageCreateParams.builder()
                .model(modelName)
                .maxTokens((long) (request.maxOutputTokens() == null
                        ? maxOutputTokens : request.maxOutputTokens()))
                .outputConfig(OutputConfig.builder().effort(effort).build())
                .addUserMessage(request.user());

        if (request.system() != null && !request.system().isBlank()) {
            builder.system(request.system());
        }
        if (thinkingEnabled) {
            builder.thinking(ThinkingConfigAdaptive.builder().build());
        } else {
            builder.thinking(ThinkingConfigDisabled.builder().build());
        }
        return builder.build();
    }
}
