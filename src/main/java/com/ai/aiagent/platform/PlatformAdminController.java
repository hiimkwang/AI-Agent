package com.ai.aiagent.platform;

import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import com.ai.aiagent.security.CurrentScope;
import com.ai.aiagent.security.GraphDirectoryClient;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/rag/admin")
@Slf4j
public class PlatformAdminController {

    private final PlatformRepository repository;
    private final PlatformService platform;

    private final DelegationService delegation;
    private final ObjectProvider<GraphDirectoryClient> graph;

    public PlatformAdminController(PlatformRepository repository, PlatformService platform,
                                   DelegationService delegation,
                                   ObjectProvider<GraphDirectoryClient> graph) {
        this.repository = repository;
        this.platform = platform;
        this.delegation = delegation;
        this.graph = graph;
    }

    /** Group display names, so the admin screen can show names instead of GUIDs. */
    @PostMapping("/group-names")
    public Map<String, Object> groupNames(@RequestBody List<String> groupIds) {
        GraphDirectoryClient client = graph.getIfAvailable();
        if (client == null || !client.isReady()) return Map.of("names", Map.of());
        return Map.of("names", client.groupNames(groupIds));
    }

    /**
     * Declare a collection for every category that documents already use but nothing declares.
     *
     * <p>Exists because the folder ingest derives one category per subfolder: a single scan can
     * produce a dozen categories, and typing each of them back into the create form by hand is
     * both tedious and easy to get wrong - a typo leaves the documents orphaned exactly as before.
     * Every collection is created closed (no ACL); read access is still a separate decision.
     */
    @PostMapping("/collections/declare-missing")
    public Map<String, Object> declareMissingCollections() {
        List<String> orphans = repository.orphanCategories();
        List<String> created = new ArrayList<>();
        for (String slug : orphans) {
            if (platform.ensureCollection(slug, CurrentScope.get().displayId())) created.add(slug);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("message", created.isEmpty()
                ? "Không có nhóm nào thiếu."
                : "Đã tạo " + created.size() + " nhóm tài liệu: " + String.join(", ", created)
                  + ". Các nhóm này CHƯA cấp quyền đọc cho ai — hãy chọn nhóm Entra được đọc ở "
                  + "bảng bên dưới, nếu không vẫn chỉ quản trị viên đọc được.");
        return out;
    }

    @GetMapping("/platform")
    public Map<String, Object> overview() {
        PlatformService.Snapshot snapshot = platform.snapshot();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("collections", snapshot.collections());
        out.put("bots", snapshot.bots());
        out.put("channelBindings", snapshot.bindings());
        out.put("grants", repository.grants());
        out.put("namespaceGrants", repository.namespaceGrants());
        out.put("orphanCategories", repository.orphanCategories());
        out.put("uncategorizedDocuments", repository.uncategorizedDocuments());
        out.put("aclConfigured", !platform.hasNoAcl());
        out.put("botsWithoutCollections", snapshot.bots().stream()
                .filter(b -> b.collectionSlugs().isEmpty())
                .map(BotDef::slug).toList());
        return out;
    }

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

    public record BulkIdsRequest(List<Long> ids) {
    }

    /**
     * Xoá nhiều nhóm tài liệu một lượt. Tài liệu KHÔNG bị xoá theo - chúng giữ nguyên cột
     * {@code category}, mà không còn nhóm nào khai slug đó nữa thì chỉ quản trị viên đọc được.
     * Nên phần trả về nói rõ số tài liệu vừa thành mồ côi, thay vì chỉ báo "đã xoá".
     */
    @PostMapping("/collections/bulk-delete")
    public Map<String, Object> bulkDeleteCollections(@RequestBody BulkIdsRequest request) {
        List<Long> ids = request.ids() == null ? List.of()
                : request.ids().stream().filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of("deleted", 0, "message", "Không có nhóm tài liệu nào được chọn.");
        }

        int deleted = 0;
        int orphanedDocuments = 0;
        List<String> slugs = new ArrayList<>();
        for (Long id : ids) {
            CollectionDef existing = platform.snapshot().collections().stream()
                    .filter(c -> c.id() == id).findFirst().orElse(null);
            if (existing == null) continue;
            orphanedDocuments += existing.documentCount();
            slugs.add(existing.slug());
            deleted += repository.deleteCollection(id) > 0 ? 1 : 0;
        }
        platform.refresh();

        String message = "Đã xoá " + deleted + " nhóm tài liệu.";
        if (orphanedDocuments > 0) {
            message += " Lưu ý: " + orphanedDocuments + " tài liệu vẫn còn trong kho nhưng giờ "
                    + "không nhóm nào khai, nên chỉ quản trị viên đọc được — xoá tài liệu đó "
                    + "hoặc khai lại nhóm.";
        }
        return Map.of("deleted", deleted, "slugs", slugs,
                "orphanedDocuments", orphanedDocuments, "message", message);
    }

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
                ? "Đã xoá giới hạn đối tượng: mọi người dùng đã xác thực đều dùng được bot "
                  + "này (quyền đọc tài liệu vẫn theo ACL của từng nhóm tài liệu)."
                : "Đã giới hạn bot cho " + (groups.size() + users.size()) + " đối tượng.");
    }

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

    /**
     * {@code principalId} is a uuid in the database. {@code principalUpn} lets an admin
     * type an email instead and have Graph resolve it - typing a GUID by hand is the
     * step people get wrong.
     */
    public record GrantRequest(String principalType, String principalId, String principalUpn,
                               String scopeType, long scopeId, String role, String displayName) {
    }

    @PostMapping("/grants")
    public Map<String, Object> grant(@RequestBody GrantRequest request) {
        String type = upper(request.principalType(), "GROUP");
        Resolved who = resolvePrincipal(type, request.principalId(), request.principalUpn(),
                request.displayName());

        repository.grant(type, who.id(),
                upper(request.scopeType(), "BOT"),
                request.scopeId(),
                upper(request.role(), "OWNER"),
                who.displayName(),
                CurrentScope.get().displayId());
        delegation.refresh();
        return Map.of("message", "Đã cấp quyền.", "principalId", who.id());
    }

    @DeleteMapping("/grants/{id}")
    public Map<String, Object> revoke(@PathVariable long id) {
        repository.revoke(id);
        delegation.refresh();
        return Map.of("message", "Đã thu hồi quyền.");
    }

    public record NamespaceGrantRequest(String principalType, String principalId,
                                        String principalUpn, @NotBlank String slugPrefix,
                                        Integer maxCollections, String displayName) {
    }

    /**
     * Hands out a slug prefix so the person can create their own collections. This is the
     * grant an admin gives once, instead of creating every collection for them.
     */
    @PostMapping("/namespace-grants")
    public Map<String, Object> grantNamespace(@RequestBody NamespaceGrantRequest request) {
        String type = upper(request.principalType(), "USER");
        Resolved who = resolvePrincipal(type, request.principalId(), request.principalUpn(),
                request.displayName());
        String prefix = normalizeSlug(request.slugPrefix());
        int max = request.maxCollections() == null || request.maxCollections() <= 0
                ? 20 : Math.min(request.maxCollections(), 500);

        repository.grantNamespace(type, who.id(), prefix, max, who.displayName(),
                CurrentScope.get().displayId());
        delegation.refresh();
        return Map.of("message", "Đã cấp tiền tố '" + prefix + "' (tối đa " + max + " nhóm).",
                "principalId", who.id(), "slugPrefix", prefix);
    }

    @DeleteMapping("/namespace-grants/{id}")
    public Map<String, Object> revokeNamespace(@PathVariable long id) {
        repository.revokeNamespace(id);
        delegation.refresh();
        return Map.of("message", "Đã thu hồi tiền tố. Các nhóm tài liệu đã tạo vẫn còn.");
    }

    private record Resolved(String id, String displayName) {
    }

    private Resolved resolvePrincipal(String type, String principalId, String principalUpn,
                                      String displayName) {
        String id = principalId == null ? "" : principalId.strip();
        if (!id.isEmpty()) return new Resolved(id, displayName);

        String upn = principalUpn == null ? "" : principalUpn.strip();
        if (upn.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cần principalId (Object ID) hoặc principalUpn (email) của đối tượng.");
        }
        if (!"USER".equals(type)) {
            throw new IllegalArgumentException(
                    "Chỉ tra được email cho principalType=USER. Nhóm phải dùng Object ID.");
        }
        GraphDirectoryClient client = graph.getIfAvailable();
        if (client == null || !client.isReady()) {
            throw new IllegalStateException("Chưa bật đăng nhập Entra nên không tra được email. "
                    + "Hãy nhập Object ID vào principalId.");
        }
        String resolved = client.objectIdOfUpn(upn);
        if (resolved == null || resolved.isBlank()) {
            throw new IllegalArgumentException("Không tìm thấy người dùng '" + upn
                    + "' trên Entra ID.");
        }
        return new Resolved(resolved,
                displayName == null || displayName.isBlank() ? upn : displayName);
    }

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
