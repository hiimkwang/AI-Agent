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

@Component
@ConditionalOnProperty(prefix = "rag.bot", name = "enabled", havingValue = "true")
@Slf4j
public class BotAccessResolver {

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
    private final ObjectProvider<EntraScopeService> entraScopes;

    public BotAccessResolver(BotProperties props, PlatformService platform,
                             ObjectProvider<EntraScopeService> entraScopes) {
        this.props = props;
        this.platform = platform;
        this.entraScopes = entraScopes;
    }

    public Resolution resolve(BotActivity activity) {
        // Danh tinh truoc, roi moi chon bot: chat rieng khong co Team lan kenh, nen cach duy nhat
        // de moi phong co tro ly rieng ma khong phai tao Azure Bot moi la chon theo nhom Entra
        // cua nguoi hoi. Chi ap dung cho chat rieng - trong kenh, cau tra loi hien cho moi nguoi
        // nen bot phai do CHO HOI quyet dinh, khong phai do ai vua go.
        Identity identity = identityOf(activity);
        if (identity.denial() != null) {
            return Resolution.deny(identity.denial());
        }

        Optional<BotDef> bot = platform.resolveBot(
                activity.recipientId(), activity.teamAadGroupId(), activity.channelId(),
                activity.isPersonal() ? identity.groups() : Set.of(),
                activity.isPersonal() ? identity.objectId() : null);
        if (bot.isEmpty()) {
            return Resolution.deny("""
                    Hệ thống chưa cấu hình trợ lý nào đang hoạt động.
                    Vui lòng liên hệ quản trị hệ thống.""");
        }

        if (!bot.get().usableBy(identity.groups(), identity.objectId())) {
            log.info("Bot '{}' denied {}: not in the bot audience.",
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
     * Lời chào phải đến từ đúng trợ lý sẽ trả lời người đó. Nếu vẫn lấy bot mặc định thì người
     * phòng Nhân sự được chào bằng lời của trợ lý chung rồi lại nói chuyện với trợ lý Nhân sự.
     */
    public Optional<BotDef> botForGreeting(BotActivity activity) {
        Set<String> groups = Set.of();
        String objectId = null;
        if (activity.isPersonal()) {
            // Loi chao la thu tu te, khong duoc phu thuoc Graph: hong thi chao bang bot mac dinh.
            try {
                Identity identity = identityOf(activity);
                if (identity.denial() == null) {
                    groups = identity.groups();
                    objectId = identity.objectId();
                }
            } catch (Exception e) {
                log.debug("Could not resolve the greeter's departments ({}), using the default bot.",
                        e.getMessage());
            }
        }
        return platform.resolveBot(activity.recipientId(), activity.teamAadGroupId(),
                activity.channelId(), groups, objectId);
    }

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

        Set<String> fallback = split(props.getUnidentifiedDepartments());
        if (fallback.isEmpty()) {
            log.debug("Could not identify the sender (aadObjectId={}, entraConfigured={}).",
                    activity.aadObjectId(), entra != null);
            return Identity.denied("""
                    Chưa xác định được tài khoản công ty của bạn nên tôi không thể tra cứu
                    tài liệu. Vui lòng liên hệ quản trị hệ thống.""");
        }
        Set<String> readable = fallback.contains("*") ? allSlugs() : fallback;
        return new Identity(null, "teams:" + activity.fromId(), Set.of(), readable, false, null);
    }

    private String noCollectionMessage(Identity identity) {
        log.info("Denied {}: no readable collection in this bot's scope.", identity.displayId());
        return """
                Tài khoản của bạn chưa được cấp quyền đọc nhóm tài liệu nào mà trợ lý này
                phục vụ. Vui lòng liên hệ quản trị hệ thống để được cấp quyền.""";
    }

    // In a Teams channel an admin must be downgraded to USER: HybridRetriever skips the
    // allowed_roles filter for admins, which would leak restricted documents to the channel.
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
