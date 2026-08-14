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

/**
 * Claude (Anthropic) qua SDK chinh thuc {@code com.anthropic:anthropic-java}.
 *
 * Vai luu y rieng cua doi Claude hien tai, khac han OpenAI:
 *   - KHONG co tham so {@code temperature} (Opus 5 tra 400 neu gui) -> dieu khien
 *     bang prompt va {@code effort} thay vi sampling.
 *   - Thinking BAT theo mac dinh tren Opus 5. Voi hoi-dap RAG (ngu canh da co san)
 *     ta tat di cho nhanh; khi tat, {@code effort} phai <= high, neu khong API tra 400.
 *   - Khi tat thinking, model co the lot the XML noi bo ra cau tra loi, nen
 *     {@code PromptBuilder} co san mot chi thi chan viec do.
 */
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

    /**
     * Tat thinking chi duoc phep o effort <= high. Neu cau hinh de xhigh/max ma
     * lai tat thinking thi ha xuong high thay vi de API tra 400.
     */
    private static OutputConfig.Effort resolveEffort(String name, boolean thinkingEnabled) {
        String v = name == null ? "low" : name.trim().toLowerCase();
        OutputConfig.Effort requested = switch (v) {
            case "medium" -> OutputConfig.Effort.MEDIUM;
            case "high" -> OutputConfig.Effort.HIGH;
            case "xhigh" -> OutputConfig.Effort.XHIGH;
            case "max" -> OutputConfig.Effort.MAX;
            default -> OutputConfig.Effort.LOW;
        };
        if (!thinkingEnabled
                && (requested.equals(OutputConfig.Effort.XHIGH) || requested.equals(OutputConfig.Effort.MAX))) {
            log.warn("Claude: tat thinking khong dung duoc voi effort={} -> ha xuong high.", v);
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
            response.stream().forEach(event -> event.contentBlockDelta()
                    .flatMap(delta -> delta.delta().text())
                    .ifPresent(textDelta -> {
                        buffer.append(textDelta.text());
                        sink.onToken(textDelta.text());
                    }));
            long ms = (System.nanoTime() - start) / 1_000_000;
            String text = buffer.toString();
            // Ban stream khong doc usage chinh xac -> uoc tinh, du de theo doi chi phi.
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
