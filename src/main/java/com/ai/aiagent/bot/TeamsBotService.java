package com.ai.aiagent.bot;

import com.ai.aiagent.chat.BotProfile;
import com.ai.aiagent.chat.ChatDtos.ChatRequest;
import com.ai.aiagent.chat.ChatDtos.ChatResponse;
import com.ai.aiagent.chat.RagChatService;
import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.config.BotProperties;
import com.ai.aiagent.config.SecurityProperties;
import com.ai.aiagent.observability.RagMetrics;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@ConditionalOnProperty(prefix = "rag.bot", name = "enabled", havingValue = "true")
@Slf4j
public class TeamsBotService {

    /** Only reached when the resolved bot has no display name of its own. */
    private static final String DEFAULT_BOT_NAME = "BSC Assistant";

    private final BotProperties props;
    private final SecurityProperties securityProps;
    private final BotAccessResolver accessResolver;
    private final BotConnectorClient connector;
    private final AdaptiveCards cards;
    private final RagChatService chatService;
    private final RagMetrics metrics;
    private final ExecutorService workers;
    private final Cache<String, AtomicInteger> rateCounters = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(2))
            .maximumSize(50_000)
            .build();

    public TeamsBotService(BotProperties props, SecurityProperties securityProps,
                           BotAccessResolver accessResolver,
                           BotConnectorClient connector, AdaptiveCards cards,
                           RagChatService chatService, RagMetrics metrics) {
        this.props = props;
        this.securityProps = securityProps;
        this.accessResolver = accessResolver;
        this.connector = connector;
        this.cards = cards;
        this.chatService = chatService;
        this.metrics = metrics;
        this.workers = Executors.newFixedThreadPool(
                Math.max(2, props.getWorkerThreads()), r -> {
                    Thread t = new Thread(r, "teams-bot");
                    t.setDaemon(true);
                    return t;
                });
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }

    public void submit(BotActivity activity) {
        if (activity.isMessage() && !withinRateLimit(activity)) {
            connector.sendText(activity, "Bạn đang hỏi hơi nhanh. Vui lòng chờ một phút rồi hỏi tiếp.");
            return;
        }
        try {
            workers.execute(() -> handleSafely(activity));
        } catch (RejectedExecutionException e) {
            log.warn("Worker queue full, dropping the message from {}.", activity.fromId());
            connector.sendText(activity,
                    "Hệ thống đang bận. Bạn vui lòng gửi lại câu hỏi sau ít phút.");
        }
    }

    private boolean withinRateLimit(BotActivity activity) {
        String who = activity.aadObjectId() != null ? activity.aadObjectId() : activity.fromId();
        String key = "u:" + who + ":" + (System.currentTimeMillis() / 60_000L);
        return rateCounters.get(key, k -> new AtomicInteger())
                .incrementAndGet() <= Math.max(1, props.getPerUserPerMinute());
    }

    private boolean withinBotRateLimit(BotDef bot) {
        int limit = props.getPerBotPerMinute();
        if (limit <= 0) return true;
        String key = "b:" + bot.slug() + ":" + (System.currentTimeMillis() / 60_000L);
        return rateCounters.get(key, k -> new AtomicInteger()).incrementAndGet() <= limit;
    }

    private static String botLabel(BotAccessResolver.Resolution resolution) {
        if (resolution == null || resolution.bot() == null) return "unknown";
        return resolution.bot().slug();
    }

    private void handleSafely(BotActivity activity) {
        BotAccessResolver.Resolution resolution = null;
        try {
            if (activity.isMessage()) {
                resolution = accessResolver.resolve(activity);
            }
            handle(activity, resolution);
        } catch (Exception e) {
            metrics.recordError(botLabel(resolution));
            log.error("Failed to handle the Teams activity", e);
            connector.sendText(activity,
                    "Xin lỗi, đã có lỗi khi tra cứu tài liệu. Bạn vui lòng thử lại sau.");
        }
    }

    private void handle(BotActivity activity, BotAccessResolver.Resolution resolution) {
        if (activity.isBotAdded()) {
            greet(activity);
            return;
        }
        if (!activity.isMessage()) {
            return;
        }

        String question = activity.text();
        if (question == null || question.isBlank()) {
            connector.sendText(activity, "Bạn hãy nhập câu hỏi giúp tôi nhé.");
            return;
        }
        if (isHelpCommand(question)) {
            greet(activity);
            return;
        }

        if (!resolution.allowed()) {
            log.info("Denied {} (scope={}, team={}): {}",
                    activity.fromId(), activity.scope(), activity.teamAadGroupId(),
                    firstLine(resolution.denial()));
            connector.sendCard(activity, cards.denied(resolution.denial()),
                    "Bạn chưa có quyền dùng chức năng này");
            return;
        }

        BotDef bot = resolution.bot();
        if (!withinBotRateLimit(bot)) {
            log.warn("Bot '{}' hit its limit of {} questions/minute, throttling {}.",
                    bot.slug(), props.getPerBotPerMinute(), activity.fromId());
            connector.sendText(activity,
                    "Trợ lý đang nhận quá nhiều câu hỏi cùng lúc. Bạn vui lòng hỏi lại sau một phút.");
            return;
        }
        connector.sendTyping(activity);

        ChatRequest request = new ChatRequest();
        request.setQuestion(truncate(question));
        request.setConversationId(activity.conversationId());

        ChatResponse response = chatService.answer(request, resolution.scope(),
                new BotProfile(bot.id(), bot.slug(), bot.personaPrompt(),
                        bot.llmProvider(), bot.llmModel()));

        log.info("Bot '{}' answered {} (scope={}, departments={}, abstained={})",
                bot.slug(), activity.fromId(), activity.scope(),
                resolution.scope().departments(), response.abstained());

        connector.sendCard(activity,
                cards.answer(response, props.getMaxCitations(), props.getMaxAnswerChars()),
                fallbackText(response));
    }

    private void greet(BotActivity activity) {
        var bot = accessResolver.botForGreeting(activity);
        String title = bot.map(BotDef::displayName)
                .filter(n -> !n.isBlank())
                .orElse(DEFAULT_BOT_NAME);
        String message = bot.map(BotDef::greeting)
                .filter(g -> !g.isBlank())
                .orElse(props.getGreeting());
        connector.sendCard(activity, cards.greeting(title, message), "Xin chào!");
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    private static boolean isHelpCommand(String text) {
        String t = text.strip().toLowerCase();
        return t.equals("/help") || t.equals("help") || t.equals("?")
                || t.equals("huong dan") || t.equals("hướng dẫn");
    }

    private String fallbackText(ChatResponse response) {
        String answer = response.answer() == null ? "" : response.answer().strip();
        String flat = answer.replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    private String truncate(String question) {
        int max = securityProps.getMaxQuestionLength();
        return question.length() <= max ? question : question.substring(0, max);
    }
}
