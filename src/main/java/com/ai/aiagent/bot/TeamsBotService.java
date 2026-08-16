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

/**
 * Xu ly mot tin nhan Teams: dinh danh nguoi gui, tra cuu, gui the tra loi.
 *
 * CHAY BAT DONG BO, va day khong phai toi uu ma la BAT BUOC. Bot Framework cho phan hoi
 * cua {@code POST /api/messages} trong thoi gian rat ngan, trong khi mot luot RAG day du
 * ton 3-4 loi goi LLM (8-20 giay). Vi vay controller tra 200 ngay, con cau tra loi that
 * duoc gui bang mot loi goi rieng toi {@code serviceUrl}. Nho co duong gui rieng nay bot
 * moi bao duoc "dang gõ" - thu ma Outgoing Webhook cu khong lam duoc.
 */
@Service
@ConditionalOnProperty(prefix = "rag.bot", name = "enabled", havingValue = "true")
@Slf4j
public class TeamsBotService {

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
        // Pool co gioi han: moi luot ton 3-4 loi goi LLM, de tha noi thi mot cuoc hop
        // ca phong cung hoi bot se quay het han muc LLM trong vai phut.
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

    /** Nhan viec va tra ve NGAY. Moi thu ton thoi gian deu chay o luong nen. */
    public void submit(BotActivity activity) {
        if (activity.isMessage() && !withinRateLimit(activity)) {
            connector.sendText(activity, "Bạn đang hỏi hơi nhanh. Vui lòng chờ một phút rồi hỏi tiếp.");
            return;
        }
        try {
            workers.execute(() -> handleSafely(activity));
        } catch (RejectedExecutionException e) {
            log.warn("Bot: hang doi day, bo qua tin nhan tu {}.", activity.fromId());
            connector.sendText(activity,
                    "Hệ thống đang bận. Bạn vui lòng gửi lại câu hỏi sau ít phút.");
        }
    }

    /**
     * Cua so co dinh 1 phut theo tung nguoi, giong {@code RateLimitFilter}.
     *
     * Uu tien {@code aadObjectId} lam khoa vi no khong doi; {@code from.id} chi la duong
     * lui khi chua xac dinh duoc tai khoan.
     */
    private boolean withinRateLimit(BotActivity activity) {
        String who = activity.aadObjectId() != null ? activity.aadObjectId() : activity.fromId();
        String key = "u:" + who + ":" + (System.currentTimeMillis() / 60_000L);
        return rateCounters.get(key, k -> new AtomicInteger())
                .incrementAndGet() <= Math.max(1, props.getPerUserPerMinute());
    }

    /**
     * Tran theo BOT, kiem tra sau khi da biet bot nao phuc vu.
     *
     * Khong gop duoc voi han muc theo nguoi o {@link #submit}: luc do chua giai duoc bot.
     * Nam sau hang doi nhung van dung cho: phan dat tien (3-4 loi goi LLM) deu nam sau
     * diem nay, con phan truoc do chi la vai truy van trong bo nho.
     */
    private boolean withinBotRateLimit(BotDef bot) {
        int limit = props.getPerBotPerMinute();
        if (limit <= 0) return true;
        String key = "b:" + bot.slug() + ":" + (System.currentTimeMillis() / 60_000L);
        return rateCounters.get(key, k -> new AtomicInteger()).incrementAndGet() <= limit;
    }

    /** Nhan bot cho metric; {@code unknown} khi loi xay ra truoc luc giai duoc bot. */
    private static String botLabel(BotAccessResolver.Resolution resolution) {
        if (resolution == null || resolution.bot() == null) return "unknown";
        return resolution.bot().slug();
    }

    // ============================================================ Noi bo

    /**
     * Xac dinh bot TRUOC khi vao xu ly, de con quy duoc loi cho dung bot.
     *
     * Truoc day {@code resolve} nam trong {@code handle}, nen khi co loi thi khong con
     * biet bot nao - moi loi cua moi bot don vao mot chuoi so lieu duy nhat, dung thu
     * so lieu can nhat de biet MOT bot dang hong.
     */
    private void handleSafely(BotActivity activity) {
        BotAccessResolver.Resolution resolution = null;
        try {
            if (activity.isMessage()) {
                resolution = accessResolver.resolve(activity);
            }
            handle(activity, resolution);
        } catch (Exception e) {
            metrics.recordError(botLabel(resolution));
            log.error("Bot: loi khi xu ly tin nhan Teams", e);
            // Khong bao gio de chi tiet loi noi bo ra ngoai - cung nguyen tac voi
            // ApiExceptionHandler.
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
            log.info("Bot: tu choi {} (scope={}, team={}): {}",
                    activity.fromId(), activity.scope(), activity.teamAadGroupId(),
                    firstLine(resolution.denial()));
            connector.sendCard(activity, cards.denied(resolution.denial()),
                    "Bạn chưa có quyền dùng chức năng này");
            return;
        }

        BotDef bot = resolution.bot();
        if (!withinBotRateLimit(bot)) {
            log.warn("Bot '{}': vuot han muc {} cau/phut, tam hoan {}.",
                    bot.slug(), props.getPerBotPerMinute(), activity.fromId());
            connector.sendText(activity,
                    "Trợ lý đang nhận quá nhiều câu hỏi cùng lúc. Bạn vui lòng hỏi lại sau một phút.");
            return;
        }
        connector.sendTyping(activity);

        ChatRequest request = new ChatRequest();
        request.setQuestion(truncate(question));
        // Giu ngu canh hoi thoai theo dung cuoc tro chuyen tren Teams: hoi "con cai kia
        // thi sao?" van hieu duoc. Webhook cu truyen conversationId = null nen moi cau
        // deu la mot phien moi.
        request.setConversationId(activity.conversationId());

        ChatResponse response = chatService.answer(request, resolution.scope(),
                new BotProfile(bot.id(), bot.slug(), bot.personaPrompt(),
                        bot.llmProvider(), bot.llmModel()));

        log.info("Bot '{}' tra loi {} (scope={}, tap={}, tu choi={})",
                bot.slug(), activity.fromId(), activity.scope(),
                resolution.scope().departments(), response.abstained());

        connector.sendCard(activity,
                cards.answer(response, props.getMaxCitations()),
                fallbackText(response));
    }

    /**
     * Loi chao lay theo BOT dang phuc vu cuoc tro chuyen, khong phai chuoi chung.
     * Bot Nhan su va bot Phap che chao khac nhau moi co y nghia la "nhieu bot".
     */
    private void greet(BotActivity activity) {
        String message = accessResolver.greetingFor(activity)
                .filter(g -> !g.isBlank())
                .orElse(props.getGreeting());
        connector.sendCard(activity, cards.greeting(message), "Xin chào!");
    }

    private static String firstLine(String text) {
        if (text == null) return "";
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    /** Vai lenh go tay hay gap; khong dung LLM de doan y dinh cho nhung viec don gian nay. */
    private static boolean isHelpCommand(String text) {
        String t = text.strip().toLowerCase();
        return t.equals("/help") || t.equals("help") || t.equals("?")
                || t.equals("huong dan") || t.equals("hướng dẫn");
    }

    /**
     * Chuoi {@code text} di kem the: Teams dung no cho thong bao day va cho client khong
     * ve duoc Adaptive Card. De rong thi thong bao day chi hien "Sent a card".
     */
    private String fallbackText(ChatResponse response) {
        String answer = response.answer() == null ? "" : response.answer().strip();
        String flat = answer.replaceAll("\\s+", " ");
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /**
     * Cat cau hoi qua dai thay vi de {@code RagChatService} nem loi.
     *
     * Dung {@code rag.security.max-question-length} chu khong tu dat mot con so rieng:
     * hai gioi han lech nhau thi bot se bao "loi he thong" cho mot cau hoi ma API van
     * chap nhan, rat kho chan doan.
     */
    private String truncate(String question) {
        int max = securityProps.getMaxQuestionLength();
        return question.length() <= max ? question : question.substring(0, max);
    }
}
