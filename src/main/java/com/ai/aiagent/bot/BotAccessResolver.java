package com.ai.aiagent.bot;

import com.ai.aiagent.config.BotProperties;
import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformService;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.security.EntraScopeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Quyet dinh mot tin nhan Teams do BOT NAO phuc vu va duoc doc nhung tap tai lieu nao.
 *
 * Pham vi cuoi cung la GIAO cua ba thu, chat hon bat ky thu nao trong so do:
 *
 *   (1) bot duoc gan nhung collection nao      - chinh sach, bang rag_bot_collections
 *   (2) nguoi hoi doc duoc nhung collection nao - danh tinh, tu nhom Entra
 *   (3) ngu canh cho phep cong khai den dau     - chi ap dung trong channel
 *
 * (3) la cho de sai nhat. Cau tra loi trong channel hien ra cho MOI thanh vien channel.
 * Neu bot dung quyen ca nhan cua nguoi hoi thi mot can bo Nhan su @mention bot trong
 * channel cong khai se phat tan tai lieu Nhan su cho ca channel - trong khi bot lam
 * hoan toan dung ACL cua nguoi hoi. Vi vay trong channel con phai giao them voi tap
 * collection co {@code channel_allowed = true}.
 *
 * Ngoai ra trong channel phai HA quyen ADMIN xuong USER. Chi thu hep danh sach collection
 * la CHUA DU: {@code HybridRetriever} loc {@code allowed_roles} bang
 * {@code isAdmin() ? Set.of() : roles()}, tuc ADMIN bo qua ACL muc tai lieu, va mot quan
 * tri vien hoi trong channel se keo tai lieu han che ra cho ca kenh.
 */
@Component
@ConditionalOnProperty(prefix = "rag.bot", name = "enabled", havingValue = "true")
@Slf4j
public class BotAccessResolver {

    /**
     * @param scope  pham vi da tinh; null khi bi tu choi
     * @param bot    bot phuc vu cuoc tro chuyen; null khi bi tu choi
     * @param denial ly do tu choi de hien cho nguoi dung; null khi duoc phep
     */
    public record Resolution(AccessScope scope, BotDef bot, String denial) {
        public boolean allowed() {
            return scope != null;
        }

        static Resolution allow(AccessScope scope, BotDef bot) {
            return new Resolution(scope, bot, null);
        }

        static Resolution deny(String reason) {
            return new Resolution(null, null, reason);
        }
    }

    private final BotProperties props;
    private final PlatformService platform;
    /** Vang mat khi {@code rag.entra.enabled=false} - khi do khong dinh danh duoc ai. */
    private final ObjectProvider<EntraScopeService> entraScopes;

    public BotAccessResolver(BotProperties props, PlatformService platform,
                             ObjectProvider<EntraScopeService> entraScopes) {
        this.props = props;
        this.platform = platform;
        this.entraScopes = entraScopes;
    }

    public Resolution resolve(BotActivity activity) {
        Optional<BotDef> bot = platform.resolveBot(
                activity.recipientId(), activity.teamAadGroupId(), activity.channelId());
        if (bot.isEmpty()) {
            return Resolution.deny("""
                    Hệ thống chưa cấu hình trợ lý nào đang hoạt động.
                    Vui lòng liên hệ quản trị hệ thống.""");
        }

        Identity identity = identityOf(activity);
        if (identity.denial() != null) {
            return Resolution.deny(identity.denial());
        }

        if (!bot.get().usableBy(identity.groups(), identity.objectId())) {
            log.info("Bot '{}': {} khong nam trong doi tuong su dung.",
                    bot.get().slug(), identity.displayId());
            return Resolution.deny("""
                    Bạn chưa nằm trong nhóm được sử dụng trợ lý này.
                    Vui lòng liên hệ quản trị hệ thống nếu bạn cần dùng.""");
        }

        Set<String> readable = intersect(bot.get().collectionSlugs(), identity.readableSlugs());
        if (activity.isPersonal()) {
            if (readable.isEmpty()) {
                return Resolution.deny(noCollectionMessage(identity));
            }
            return Resolution.allow(scope(identity, readable, identity.admin()), bot.get());
        }

        // Channel / group chat: thu hep them bang tap duoc phep tra loi cong khai,
        // va ha quyen ADMIN.
        Set<String> publicSlugs = platform.channelAllowedSlugs();
        if (publicSlugs.isEmpty()) {
            return Resolution.deny("""
                    Chưa có nhóm tài liệu nào được phép trả lời trong kênh chung, vì câu trả
                    lời sẽ hiển thị cho mọi thành viên trong kênh.
                    Bạn hãy **nhắn riêng cho tôi** để tra cứu theo quyền của bạn.""");
        }
        Set<String> inChannel = intersect(readable, publicSlugs);
        if (inChannel.isEmpty()) {
            return Resolution.deny("""
                    Trong kênh này tôi chưa được phép tra cứu nhóm tài liệu nào thuộc quyền
                    của bạn. Bạn hãy **nhắn riêng cho tôi** để tra cứu theo quyền của bạn.""");
        }
        return Resolution.allow(scope(identity, inChannel, false), bot.get());
    }

    /**
     * Loi chao cua bot phuc vu cuoc tro chuyen nay.
     *
     * Tach rieng khoi {@link #resolve} vi loi chao duoc gui khi bot vua duoc cai, luc do
     * chua can (va chua nen) tinh quyen doc tai lieu.
     */
    public Optional<String> greetingFor(BotActivity activity) {
        return platform.resolveBot(activity.recipientId(), activity.teamAadGroupId(),
                        activity.channelId())
                .map(BotDef::greeting);
    }

    // ============================================================ Danh tinh

    /**
     * @param admin        chi co y nghia trong chat rieng - trong channel luon bi ha
     * @param readableSlugs tap collection nguoi nay doc duoc, TRUOC khi giao voi bot
     */
    private record Identity(String objectId, String displayId, Set<String> groups,
                            Set<String> readableSlugs, boolean admin, String denial) {
        static Identity denied(String reason) {
            return new Identity(null, null, Set.of(), Set.of(), false, reason);
        }
    }

    private Identity identityOf(BotActivity activity) {
        EntraScopeService entra = entraScopes.getIfAvailable();

        if (entra != null && activity.aadObjectId() != null) {
            AccessScope scope = entra.scopeOf(activity.aadObjectId(), null, List.of());
            Set<String> readable = scope.allDepartments()
                    ? allSlugs()
                    : scope.departments();
            return new Identity(activity.aadObjectId(),
                    scope.displayId(), scope.entraGroups(), readable, scope.isAdmin(), null);
        }

        // Khong xac dinh duoc nguoi dung. MAC DINH TU CHOI: bot khong biet nguoi hoi la ai
        // thi khong the thuc thi ACL.
        Set<String> fallback = split(props.getUnidentifiedDepartments());
        if (fallback.isEmpty()) {
            log.debug("Bot: khong xac dinh duoc nguoi dung (aadObjectId={}, entra={}).",
                    activity.aadObjectId(), entra != null);
            return Identity.denied("""
                    Chưa xác định được tài khoản công ty của bạn nên tôi không thể tra cứu
                    tài liệu. Vui lòng liên hệ quản trị hệ thống.""");
        }
        Set<String> readable = fallback.contains("*") ? allSlugs() : fallback;
        return new Identity(null, "teams:" + activity.fromId(), Set.of(), readable, false, null);
    }

    private String noCollectionMessage(Identity identity) {
        log.info("Bot: {} khong doc duoc collection nao cua bot.", identity.displayId());
        return """
                Tài khoản của bạn chưa được cấp quyền đọc nhóm tài liệu nào mà trợ lý này
                phục vụ. Vui lòng liên hệ quản trị hệ thống để được cấp quyền.""";
    }

    private AccessScope scope(Identity identity, Set<String> slugs, boolean admin) {
        return new AccessScope(
                identity.objectId() != null ? identity.objectId() : identity.displayId(),
                null,
                admin ? Set.of("ADMIN", "USER") : Set.of("USER"),
                slugs, false, identity.groups());
    }

    private Set<String> allSlugs() {
        Set<String> out = new LinkedHashSet<>();
        platform.snapshot().collections().stream()
                .filter(c -> c.isActive())
                .forEach(c -> out.add(c.slug()));
        return out;
    }

    private static Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : a) {
            if (b.stream().anyMatch(v::equalsIgnoreCase)) out.add(v);
        }
        return out;
    }

    private static Set<String> split(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .forEach(out::add);
        return out;
    }
}
