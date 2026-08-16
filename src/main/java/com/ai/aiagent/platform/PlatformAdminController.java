package com.ai.aiagent.platform;

import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import com.ai.aiagent.security.CurrentScope;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Quan tri nen tang nhieu bot: tap tai lieu, bot, quyen doc, doi tuong su dung,
 * rang buoc Team -> bot.
 *
 * Nam duoi {@code /api/v1/rag/admin/**} nen mac nhien chi ADMIN goi duoc - xem
 * {@code SecurityConfig}. Phan quyen min hon (chu bot chi sua duoc bot cua minh) dung
 * bang {@code rag_grants}, se noi vao khi giao dien tach vai tro Editor.
 */
@RestController
@RequestMapping("/api/v1/rag/admin")
@Slf4j
public class PlatformAdminController {

    private final PlatformRepository repository;
    private final PlatformService platform;

    public PlatformAdminController(PlatformRepository repository, PlatformService platform) {
        this.repository = repository;
        this.platform = platform;
    }

    // ============================================================ Tong quan

    @GetMapping("/platform")
    public Map<String, Object> overview() {
        PlatformService.Snapshot snapshot = platform.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collections", snapshot.collections());
        out.put("bots", snapshot.bots());
        out.put("channelBindings", snapshot.bindings());
        out.put("grants", repository.grants());
        // Category co tai lieu nhung chua khai bao collection => khong ai doc duoc, va
        // trieu chung la "nap tai lieu roi ma hoi khong ra". Bao thang tren giao dien.
        out.put("orphanCategories", repository.orphanCategories());
        out.put("aclConfigured", !platform.hasNoAcl());
        // Bot khong duoc gan tap tai lieu nao thi tu choi MOI cau hoi. Cai dat moi rat
        // de roi vao trang thai nay, va thong bao tu choi khong noi len duoc nguyen nhan.
        out.put("botsWithoutCollections", snapshot.bots().stream()
                .filter(b -> b.collectionSlugs().isEmpty())
                .map(BotDef::slug).toList());
        return out;
    }

    // ============================================================ Collection

    public record CollectionRequest(
            @NotBlank String slug,
            String name,
            String description,
            Boolean channelAllowed,
            String status) {
    }

    @PostMapping("/collections")
    public Map<String, Object> createCollection(@RequestBody CollectionRequest request) {
        String slug = normalizeSlug(request.slug());
        if (platform.snapshot().collection(slug).isPresent()) {
            throw new IllegalArgumentException("Nhóm tài liệu '" + slug + "' đã tồn tại.");
        }
        long id = repository.createCollection(slug,
                blankTo(request.name(), slug), request.description(),
                Boolean.TRUE.equals(request.channelAllowed()),
                CurrentScope.get().displayId());
        platform.refresh();
        return Map.of("message", "Đã tạo nhóm tài liệu '" + slug + "'.", "id", id, "slug", slug);
    }

    @PutMapping("/collections/{id}")
    public Map<String, Object> updateCollection(@PathVariable long id,
                                                @RequestBody CollectionRequest request) {
        CollectionDef existing = requireCollection(id);
        repository.updateCollection(id,
                blankTo(request.name(), existing.name()), request.description(),
                request.channelAllowed() == null ? existing.channelAllowed() : request.channelAllowed(),
                blankTo(request.status(), existing.status()));
        platform.refresh();
        return Map.of("message", "Đã cập nhật nhóm tài liệu.");
    }

    /**
     * Xoa CAU HINH nhom tai lieu, KHONG xoa tai lieu.
     *
     * Tai lieu la du lieu, cau hinh la chinh sach. Xoa nham chinh sach thi khai bao lai
     * trong mot phut; xoa nham tai lieu thi phai nap lai ca kho.
     */
    @DeleteMapping("/collections/{id}")
    public Map<String, Object> deleteCollection(@PathVariable long id) {
        CollectionDef existing = requireCollection(id);
        repository.deleteCollection(id);
        platform.refresh();
        return Map.of("message", "Đã xoá cấu hình nhóm '" + existing.slug()
                + "'. Tài liệu vẫn còn trong kho, nhưng sẽ không ai đọc được cho tới khi "
                + "khai báo lại nhóm này.");
    }

    public record AclRequest(List<String> groupIds, List<String> groupNames) {
    }

    @PutMapping("/collections/{id}/acl")
    public Map<String, Object> setAcl(@PathVariable long id, @RequestBody AclRequest request) {
        requireCollection(id);
        List<String> ids = PlatformRepository.safeList(request.groupIds());
        repository.setCollectionAcl(id, ids, request.groupNames(),
                CurrentScope.get().displayId());
        platform.refresh();
        return Map.of("message", ids.isEmpty()
                ? "Đã xoá toàn bộ quyền đọc. Nhóm này giờ không ai đọc được (trừ quản trị)."
                : "Đã cấp quyền đọc cho " + ids.size() + " nhóm Entra.");
    }

    // ============================================================ Bot

    public record BotRequest(
            @NotBlank String slug,
            String displayName,
            String description,
            String teamsAppId,
            String personaPrompt,
            String greeting,
            String llmProvider,
            String llmModel,
            String status) {
    }

    @PostMapping("/bots")
    public Map<String, Object> createBot(@RequestBody BotRequest request) {
        String slug = normalizeSlug(request.slug());
        if (platform.snapshot().bot(slug).isPresent()) {
            throw new IllegalArgumentException("Bot '" + slug + "' đã tồn tại.");
        }
        long id = repository.createBot(slug, blankTo(request.displayName(), slug),
                request.description(), CurrentScope.get().displayId());
        platform.refresh();
        return Map.of("message", "Đã tạo bot '" + slug + "'. "
                + "Hãy gán nhóm tài liệu cho bot trước khi dùng.", "id", id, "slug", slug);
    }

    @PutMapping("/bots/{id}")
    public Map<String, Object> updateBot(@PathVariable long id, @RequestBody BotRequest request) {
        BotDef existing = requireBot(id);
        repository.updateBot(id,
                blankTo(request.displayName(), existing.displayName()),
                request.description(), request.teamsAppId(), request.personaPrompt(),
                request.greeting(), request.llmProvider(), request.llmModel(),
                blankTo(request.status(), existing.status()));
        platform.refresh();
        return Map.of("message", "Đã cập nhật bot '" + existing.slug() + "'.");
    }

    @DeleteMapping("/bots/{id}")
    public Map<String, Object> deleteBot(@PathVariable long id) {
        BotDef existing = requireBot(id);
        if (repository.deleteBot(id) == 0) {
            // Xoa bot mac dinh se lam moi cuoc tro chuyen khong khop luat nao mat bot.
            throw new IllegalArgumentException(
                    "Không xoá được bot mặc định. Hãy đặt bot khác làm mặc định trước.");
        }
        platform.refresh();
        return Map.of("message", "Đã xoá bot '" + existing.slug() + "'.");
    }

    @PostMapping("/bots/{id}/default")
    public Map<String, Object> makeDefault(@PathVariable long id) {
        BotDef existing = requireBot(id);
        repository.setDefaultBot(id);
        platform.refresh();
        return Map.of("message", "Bot '" + existing.slug()
                + "' giờ là bot mặc định cho chat riêng và mọi kênh chưa gán bot.");
    }

    @PutMapping("/bots/{id}/collections")
    public Map<String, Object> setBotCollections(@PathVariable long id,
                                                 @RequestBody List<String> slugs) {
        requireBot(id);
        repository.setBotCollections(id, PlatformRepository.safeList(slugs));
        platform.refresh();
        return Map.of("message", "Đã gán " + PlatformRepository.safeList(slugs).size()
                + " nhóm tài liệu cho bot.");
    }

    public record AudienceRequest(List<String> groupIds, List<String> userIds,
                                  Map<String, String> names) {
    }

    @PutMapping("/bots/{id}/audience")
    public Map<String, Object> setAudience(@PathVariable long id,
                                           @RequestBody AudienceRequest request) {
        requireBot(id);
        List<String> groups = PlatformRepository.safeList(request.groupIds());
        List<String> users = PlatformRepository.safeList(request.userIds());
        repository.setBotAudience(id, groups, users, request.names());
        platform.refresh();
        return Map.of("message", groups.isEmpty() && users.isEmpty()
                // Rong = mo, khac han ACL collection (rong = dong). Noi ro de khong ai
                // tuong minh vua khoa bot lai.
                ? "Đã xoá giới hạn đối tượng: mọi người dùng đã xác thực đều dùng được bot "
                  + "này (quyền đọc tài liệu vẫn theo ACL của từng nhóm tài liệu)."
                : "Đã giới hạn bot cho " + (groups.size() + users.size()) + " đối tượng.");
    }

    // ============================================================ Rang buoc kenh

    public record ChannelRequest(long botId, @NotBlank String teamAadGroupId, String channelId) {
    }

    @PostMapping("/bot-channels")
    public Map<String, Object> bindChannel(@RequestBody ChannelRequest request) {
        requireBot(request.botId());
        repository.bindChannel(request.botId(), request.teamAadGroupId(), request.channelId(),
                CurrentScope.get().displayId());
        platform.refresh();
        return Map.of("message", "Đã gán bot cho Team này.");
    }

    @DeleteMapping("/bot-channels/{id}")
    public Map<String, Object> unbindChannel(@PathVariable long id) {
        repository.unbindChannel(id);
        platform.refresh();
        return Map.of("message", "Đã bỏ gán. Team này sẽ dùng bot mặc định.");
    }

    // ============================================================ Grant

    public record GrantRequest(String principalType, @NotBlank String principalId,
                               String scopeType, long scopeId, String role, String displayName) {
    }

    @PostMapping("/grants")
    public Map<String, Object> grant(@RequestBody GrantRequest request) {
        repository.grant(
                upper(request.principalType(), "GROUP"),
                request.principalId(),
                upper(request.scopeType(), "BOT"),
                request.scopeId(),
                upper(request.role(), "OWNER"),
                request.displayName(),
                CurrentScope.get().displayId());
        return Map.of("message", "Đã cấp quyền.");
    }

    @DeleteMapping("/grants/{id}")
    public Map<String, Object> revoke(@PathVariable long id) {
        repository.revoke(id);
        return Map.of("message", "Đã thu hồi quyền.");
    }

    // ============================================================ Tro giup

    private CollectionDef requireCollection(long id) {
        return platform.snapshot().collections().stream()
                .filter(c -> c.id() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Không tìm thấy nhóm tài liệu id=" + id));
    }

    private BotDef requireBot(long id) {
        return platform.snapshot().botById(id)
                .orElseThrow(() -> new IllegalArgumentException("Không tìm thấy bot id=" + id));
    }

    /**
     * Slug phai khop cot {@code category} cua tai lieu, ma cot do luon duoc ghi bang chu
     * thuong khong dau. Chuan hoa o day de khong sinh ra collection "Nhan-Su" khong bao
     * gio khop tai lieu nao.
     */
    static String normalizeSlug(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Mã nhóm không được để trống.");
        }
        String slug = raw.strip().toLowerCase(Locale.ROOT).replaceAll("\\s+", "-");
        if (!slug.matches("[a-z0-9._-]+")) {
            throw new IllegalArgumentException(
                    "Mã nhóm chỉ được dùng chữ thường không dấu, số và các ký tự - _ .");
        }
        return slug;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static String upper(String value, String fallback) {
        return value == null || value.isBlank()
                ? fallback : value.strip().toUpperCase(Locale.ROOT);
    }
}
