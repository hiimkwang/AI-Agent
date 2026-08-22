package com.ai.aiagent.chat;

import com.ai.aiagent.config.RagProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Greetings retrieve nothing, so without this branch they reach the relevance gate and
 * are answered with "khong tim thay tai lieu" - correct for a real question, absurd for
 * "hello".
 */
@Component
@Slf4j
public class SmallTalkDetector {

    /**
     * The whole message must be a greeting and nothing else: "chao ban" gets the welcome
     * text, "chao ban, quy trinh cap phat mat bao lau?" still goes through retrieval.
     */
    public static final String DEFAULT_PATTERN =
            "(?iu)^\\s*(?:hi+|hello+|hey+|helo|alo+|yo"
                    + "|good\\s+(?:morning|afternoon|evening)"
                    + "|(?:xin\\s+)?ch[àa]o(?:\\s+(?:b[ạa]n|bot|anh|ch[ịi]|em|c[ảa]\\s+nh[àa]|m[ọo]i\\s+ng[ưu][ờo]i))?"
                    + "|c[ảa]m\\s+[ơo]n(?:\\s+b[ạa]n)?|thanks?(?:\\s+you)?"
                    + "|t[ạa]m\\s+bi[ệe]t|bye"
                    + "|b[ạa]n\\s+l[àa]\\s+ai|b[ạa]n\\s+l[àa]m\\s+[đd][ưu][ợo]c\\s+g[ìi]"
                    + "|gi[úu]p|help|h[ưu][ớo]ng\\s+d[ẫa]n"
                    + ")\\s*[!?.,~]*\\s*$";

    public static final String DEFAULT_REPLY = """
            Chào bạn 👋 Mình là trợ lý tra cứu tài liệu nội bộ.

            Bạn cứ hỏi thẳng nội dung cần tìm, ví dụ: *"Quy trình cấp phát thiết bị mất bao lâu?"*

            Mình chỉ trả lời dựa trên tài liệu đã được nạp và luôn kèm nguồn để bạn kiểm \
            chứng — câu nào không có trong tài liệu thì mình nói rõ là không tìm thấy.""";

    // Longer than this is a real question even if it starts with a greeting, and it also
    // caps the work the regex can do on hostile input.
    private static final int MAX_LENGTH = 64;

    private record Compiled(String source, Pattern pattern) {
    }

    private final RagProperties props;
    private final AtomicReference<Compiled> cache = new AtomicReference<>();

    public SmallTalkDetector(RagProperties props) {
        this.props = props;
    }

    public boolean matches(String question) {
        if (!props.getChat().isSmallTalkEnabled()) return false;
        if (question == null) return false;
        String q = question.strip();
        if (q.isEmpty() || q.length() > MAX_LENGTH) return false;

        Pattern pattern = pattern();
        return pattern != null && pattern.matcher(q).matches();
    }

    public String reply() {
        String configured = props.getChat().getSmallTalkReply();
        return configured == null || configured.isBlank() ? DEFAULT_REPLY : configured.strip();
    }

    private Pattern pattern() {
        String source = props.getChat().getSmallTalkPattern();
        if (source == null || source.isBlank()) source = DEFAULT_PATTERN;

        Compiled current = cache.get();
        if (current != null && current.source().equals(source)) return current.pattern();

        try {
            Compiled compiled = new Compiled(source, Pattern.compile(source));
            cache.set(compiled);
            return compiled.pattern();
        } catch (RuntimeException e) {
            // A bad pattern must not take chat down with it.
            log.warn("Invalid chat.smallTalkPattern ({}), falling back to the built-in pattern.",
                    e.getMessage());
            Compiled fallback = new Compiled(source, Pattern.compile(DEFAULT_PATTERN));
            cache.set(fallback);
            return fallback.pattern();
        }
    }
}
