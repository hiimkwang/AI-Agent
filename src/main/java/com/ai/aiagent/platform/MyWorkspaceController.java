package com.ai.aiagent.platform;

import com.ai.aiagent.common.NotFoundException;
import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.ingest.IngestionService;
import com.ai.aiagent.observability.RagMetrics;
import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformModels.ChannelBinding;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.security.CurrentScope;
import com.ai.aiagent.security.GraphDirectoryClient;
import com.ai.aiagent.security.PathAllowlist;
import com.ai.aiagent.store.DocumentRepository;
import com.ai.aiagent.store.StoreModels.DocumentMeta;
import com.ai.aiagent.chat.AnswerCacheService;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Workspace for people an admin delegated a collection or a bot to, via {@code rag_grants}.
 * <p>
 * Deliberately a separate namespace instead of loosening {@code /api/v1/rag/admin/**}:
 * every endpoint here re-checks ownership, and nothing an admin can do leaks in by
 * accident when a new admin endpoint is added later.
 */
@RestController
@RequestMapping("/api/v1/rag/my")
@Slf4j
public class MyWorkspaceController {

    private final DelegationService delegation;
    private final PlatformService platform;
    private final PlatformRepository repository;
    private final DocumentRepository documents;
    private final IngestionService ingestion;
    private final AnswerCacheService cache;
    private final RagMetrics metrics;
    private final RagProperties props;
    private final ObjectProvider<GraphDirectoryClient> graph;

    public MyWorkspaceController(DelegationService delegation, PlatformService platform,
                                 PlatformRepository repository, DocumentRepository documents,
                                 IngestionService ingestion, AnswerCacheService cache,
                                 RagMetrics metrics, RagProperties props,
                                 ObjectProvider<GraphDirectoryClient> graph) {
        this.delegation = delegation;
        this.platform = platform;
        this.repository = repository;
        this.documents = documents;
        this.ingestion = ingestion;
        this.cache = cache;
        this.metrics = metrics;
        this.props = props;
        this.graph = graph;
    }

    // ------------------------------------------------------------------ profile

    @GetMapping("/profile")
    public Map<String, Object> profile() {
        AccessScope scope = CurrentScope.get();
        DelegationService.Ownership own = delegation.ownershipOf(scope);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("upn", scope.upn());
        out.put("displayId", scope.displayId());
        out.put("admin", scope.isAdmin());
        out.put("delegationEnabled", delegation.enabled());
        out.put("ownsNothing", own.ownsNothing());
        out.put("groups", myGroups(scope));
        out.put("collections", own.collectionSlugs());
        out.put("botCount", own.botIds().size());
        // Prefixes the caller may create new collections under, and the quota on each.
        out.put("namespaces", delegation.namespacesOf(scope).stream()
                .map(n -> Map.of("prefix", n.slugPrefix(), "max", n.maxCollections()))
                .toList());
        out.put("canCreateCollections",
                scope.isAdmin() || !delegation.namespacesOf(scope).isEmpty());
        return out;
    }

    /**
     * The caller's own Entra groups, with display names, flagged by whether they may be
     * used as a read permission. This is what makes a group picker possible at all - the
     * token only carries object ids.
     */
    @GetMapping("/groups")
    public Map<String, Object> groups() {
        return Map.of("groups", myGroups(CurrentScope.get()));
    }

    private List<Map<String, Object>> myGroups(AccessScope scope) {
        Set<String> ids = new LinkedHashSet<>(scope.entraGroups());
        Map<String, String> names = Map.of();
        GraphDirectoryClient client = graph.getIfAvailable();
        if (client != null && !ids.isEmpty()) {
            names = client.groupNames(ids);
        }
        Set<String> denied = delegation.deniedGroups();

        List<Map<String, Object>> out = new ArrayList<>();
        for (String id : ids) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("id", id);
            g.put("name", names.getOrDefault(id, id));
            g.put("usableAsAcl", scope.isAdmin() || !denied.contains(id));
            out.add(g);
        }
        return out;
    }

    // -------------------------------------------------------------- collections

    @GetMapping("/collections")
    public Map<String, Object> collections() {
        AccessScope scope = CurrentScope.get();
        DelegationService.Ownership own = delegation.ownershipOf(scope);

        Map<String, String> names = groupNameLookup(own);
        List<Map<String, Object>> out = new ArrayList<>();
        for (CollectionDef c : platform.snapshot().collections()) {
            if (c.id() == null || !own.collectionIds().contains(c.id())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.id());
            m.put("slug", c.slug());
            m.put("name", c.name());
            m.put("description", c.description());
            m.put("channelAllowed", c.channelAllowed());
            m.put("status", c.status());
            m.put("documentCount", c.documentCount());
            m.put("acl", c.aclGroups().stream()
                    .map(id -> Map.of("id", id, "name", names.getOrDefault(id, id)))
                    .toList());
            out.add(m);
        }
        return Map.of("collections", out);
    }

    public record NewCollectionRequest(@NotBlank String slug, String name, String description,
                                       List<String> groupIds) {
    }

    /**
     * A delegated owner creates their own collections - an admin only hands out the slug
     * prefix once. The prefix matters because {@code rag_collections.slug} IS the
     * {@code category} column every search query filters on: a shared namespace where two
     * departments could both claim "quy-trinh" is not fixable after documents are ingested.
     */
    @PostMapping("/collections")
    public Map<String, Object> createCollection(@RequestBody NewCollectionRequest request) {
        AccessScope scope = CurrentScope.get();
        String slug = PlatformAdminController.normalizeSlug(request.slug());

        delegation.requireNamespace(scope, slug, delegation.countUnderPrefixFor(scope, slug));
        if (platform.snapshot().collection(slug).isPresent()) {
            throw new IllegalArgumentException("Nhóm tài liệu '" + slug + "' đã tồn tại.");
        }
        List<String> acl = PlatformRepository.safeList(request.groupIds());
        delegation.requireAclTargets(scope, acl);

        long id = repository.createCollection(slug,
                request.name() == null || request.name().isBlank() ? slug : request.name(),
                request.description(), false, scope.displayId());

        // Without this the creator would immediately lose access to what they just made.
        if (isUuid(scope.clientId())) {
            repository.grant("USER", scope.clientId(), "COLLECTION", id, "OWNER",
                    scope.displayId(), scope.displayId());
        }
        if (!acl.isEmpty()) {
            repository.setCollectionAcl(id, acl, groupNamesFor(acl), scope.displayId());
        }
        platform.refresh();
        delegation.refresh();
        log.info("Delegated: {} created collection '{}' (id={}) readable by {} group(s).",
                scope.displayId(), slug, id, acl.size());

        return Map.of("message", acl.isEmpty()
                        ? "Đã tạo nhóm '" + slug + "'. Chưa cấp quyền đọc cho nhóm nào nên "
                          + "hiện chỉ bạn và quản trị đọc được."
                        : "Đã tạo nhóm '" + slug + "' và cấp quyền đọc cho " + acl.size() + " nhóm.",
                "id", id, "slug", slug);
    }

    /** Only when empty: deleting a collection that has documents hides them from everyone. */
    @DeleteMapping("/collections/{id}")
    public Map<String, Object> deleteCollection(@PathVariable long id) {
        AccessScope scope = CurrentScope.get();
        delegation.requireCollection(scope, id);
        CollectionDef c = collectionById(id);
        if (c.documentCount() > 0) {
            throw new IllegalArgumentException("Nhóm '" + c.slug() + "' còn " + c.documentCount()
                    + " tài liệu. Xoá hết tài liệu trước, hoặc đề nghị quản trị xoá.");
        }
        repository.deleteCollection(id);
        platform.refresh();
        delegation.refresh();
        return Map.of("message", "Đã xoá nhóm tài liệu '" + c.slug() + "'.");
    }

    public record AclRequest(List<String> groupIds) {
    }

    @PutMapping("/collections/{id}/acl")
    public Map<String, Object> setAcl(@PathVariable long id, @RequestBody AclRequest request) {
        AccessScope scope = CurrentScope.get();
        delegation.requireCollection(scope, id);

        List<String> ids = PlatformRepository.safeList(request.groupIds());
        delegation.requireAclTargets(scope, ids);

        repository.setCollectionAcl(id, ids, groupNamesFor(ids), scope.displayId());
        platform.refresh();
        log.info("Delegated: {} set the ACL of collection id={} to {} Entra group(s).",
                scope.displayId(), id, ids.size());

        return Map.of("message", ids.isEmpty()
                ? "Đã xoá toàn bộ quyền đọc. Nhóm này giờ không ai đọc được (trừ quản trị)."
                : "Đã cấp quyền đọc cho " + ids.size() + " nhóm.");
    }

    // ---------------------------------------------------------------- documents

    @GetMapping("/collections/{id}/documents")
    public Map<String, Object> documentsOf(@PathVariable long id,
                                           @RequestParam(required = false) String search,
                                           @RequestParam(defaultValue = "50") int limit,
                                           @RequestParam(defaultValue = "0") int offset) {
        AccessScope scope = CurrentScope.get();
        delegation.requireCollection(scope, id);
        CollectionDef c = collectionById(id);

        List<DocumentMeta> found = documents.list(c.slug(), search, limit, offset);
        return Map.of("slug", c.slug(), "limit", limit, "offset", offset,
                "documents", found.stream().map(MyWorkspaceController::toMap).toList());
    }

    @PostMapping(value = "/collections/{id}/documents",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@PathVariable long id,
                                      @RequestParam("file") MultipartFile file,
                                      @RequestParam(value = "force", defaultValue = "false")
                                      boolean force) throws IOException {
        AccessScope scope = CurrentScope.get();
        delegation.requireCollection(scope, id);
        CollectionDef c = collectionById(id);

        if (file.isEmpty()) {
            throw new IllegalArgumentException("File upload bi trong.");
        }
        String fileName = PathAllowlist.sanitizeFileName(file.getOriginalFilename());

        // Category is taken from the collection, never from the request: letting the
        // caller name it would let them write into another department's category.
        IngestionService.IngestResult result = ingestion.ingest(file.getBytes(), fileName,
                IngestionService.IngestOptions.builder()
                        .category(c.slug())
                        .allowedRoles(List.of())
                        .createdBy(scope.displayId())
                        .force(force)
                        .build());

        if (result.outcome() == IngestionService.Outcome.INGESTED) {
            metrics.recordIngest(result.chunkCount());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("outcome", result.outcome().name());
        out.put("documentId", result.documentId() > 0 ? result.documentId() : null);
        out.put("fileName", result.fileName());
        out.put("chunkCount", result.chunkCount());
        out.put("warnings", result.warnings());
        out.put("message", switch (result.outcome()) {
            case INGESTED -> "Đã nạp " + result.chunkCount() + " đoạn từ '" + fileName
                    + "' vào nhóm '" + c.slug() + "'.";
            case SKIPPED_UNCHANGED -> "Bỏ qua '" + fileName + "': nội dung không đổi.";
            case EMPTY -> "Không nạp được '" + fileName + "': không có nội dung sau chuyển đổi.";
        });
        return out;
    }

    @DeleteMapping("/documents/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable long docId) {
        AccessScope scope = CurrentScope.get();
        DocumentMeta meta = documents.findById(docId)
                .orElseThrow(() -> new NotFoundException("Khong tim thay tai lieu id=" + docId));
        delegation.requireSlug(scope, meta.category());

        documents.deleteById(docId);
        int purged = cache.clear();
        log.info("Delegated: {} deleted document {} ({}), {} cache entries purged.",
                scope.displayId(), docId, meta.fileName(), purged);
        return Map.of("message", "Đã xoá tài liệu '" + meta.fileName() + "'.");
    }

    // --------------------------------------------------------------------- bots

    @GetMapping("/bots")
    public Map<String, Object> bots() {
        AccessScope scope = CurrentScope.get();
        DelegationService.Ownership own = delegation.ownershipOf(scope);

        List<Map<String, Object>> out = new ArrayList<>();
        for (BotDef b : platform.snapshot().bots()) {
            if (b.id() == null || !own.botIds().contains(b.id())) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.id());
            m.put("slug", b.slug());
            m.put("displayName", b.displayName());
            m.put("description", b.description());
            m.put("personaPrompt", b.personaPrompt());
            m.put("greeting", b.greeting());
            m.put("status", b.status());
            m.put("collectionSlugs", b.collectionSlugs());
            m.put("channels", platform.snapshot().bindings().stream()
                    .filter(x -> x.botId() != null && x.botId().equals(b.id()))
                    .map(x -> Map.of("id", x.id(), "teamAadGroupId", x.teamAadGroupId(),
                            "channelId", x.channelId() == null ? "" : x.channelId()))
                    .toList());
            out.add(m);
        }
        return Map.of("bots", out, "manageableCollections", own.collectionSlugs());
    }

    public record BotRequest(@NotBlank String slug, String displayName, String description,
                             String personaPrompt, String greeting) {
    }

    /** A collection owner may create their own bot and becomes its owner. */
    @PostMapping("/bots")
    public Map<String, Object> createBot(@RequestBody BotRequest request) {
        AccessScope scope = CurrentScope.get();
        DelegationService.Ownership own = delegation.ownershipOf(scope);
        if (own.collectionSlugs().isEmpty()) {
            throw new AccessDeniedException(
                    "Bạn phải được giao ít nhất một nhóm tài liệu trước khi tạo bot.");
        }
        String slug = PlatformAdminController.normalizeSlug(request.slug());
        if (platform.snapshot().bot(slug).isPresent()) {
            throw new IllegalArgumentException("Bot '" + slug + "' đã tồn tại.");
        }
        long id = repository.createBot(slug,
                request.displayName() == null || request.displayName().isBlank()
                        ? slug : request.displayName(),
                request.description(), scope.displayId());
        repository.updateBot(id,
                request.displayName() == null || request.displayName().isBlank()
                        ? slug : request.displayName(),
                request.description(), null,
                request.personaPrompt(), request.greeting(), null, null, "ACTIVE");

        // Without this grant the creator could not manage the bot they just made.
        if (isUuid(scope.clientId())) {
            repository.grant("USER", scope.clientId(), "BOT", id, "OWNER",
                    scope.displayId(), scope.displayId());
        }
        platform.refresh();
        delegation.refresh();
        log.info("Delegated: {} created bot '{}' (id={}).", scope.displayId(), slug, id);
        return Map.of("message", "Đã tạo bot '" + slug
                + "'. Hãy gán nhóm tài liệu và Team cho nó.", "id", id, "slug", slug);
    }

    @PutMapping("/bots/{id}")
    public Map<String, Object> updateBot(@PathVariable long id, @RequestBody BotRequest request) {
        AccessScope scope = CurrentScope.get();
        delegation.requireBot(scope, id);
        if (!props.getGrants().isOwnersMayEditPersona() && !scope.isAdmin()) {
            throw new AccessDeniedException("Quản trị đã tắt quyền sửa persona của bot.");
        }
        BotDef b = platform.snapshot().botById(id)
                .orElseThrow(() -> new NotFoundException("Khong tim thay bot id=" + id));

        repository.updateBot(id,
                blankTo(request.displayName(), b.displayName()),
                request.description() == null ? b.description() : request.description(),
                b.teamsAppId(),
                request.personaPrompt() == null ? b.personaPrompt() : request.personaPrompt(),
                request.greeting() == null ? b.greeting() : request.greeting(),
                b.llmProvider(), b.llmModel(), b.status());
        platform.refresh();
        return Map.of("message", "Đã cập nhật bot '" + b.slug() + "'.");
    }

    public record BotCollectionsRequest(List<String> slugs) {
    }

    @PutMapping("/bots/{id}/collections")
    public Map<String, Object> setBotCollections(@PathVariable long id,
                                                 @RequestBody BotCollectionsRequest request) {
        AccessScope scope = CurrentScope.get();
        delegation.requireBot(scope, id);
        DelegationService.Ownership own = delegation.ownershipOf(scope);

        List<String> slugs = PlatformRepository.safeList(request.slugs());
        for (String slug : slugs) {
            if (!own.collectionSlugs().contains(slug.toLowerCase())) {
                throw new AccessDeniedException("Bạn chỉ gán được nhóm tài liệu mình quản lý. "
                        + "Nhóm không hợp lệ: " + slug);
            }
        }
        repository.setBotCollections(id, slugs);
        platform.refresh();
        return Map.of("message", slugs.isEmpty()
                ? "Đã bỏ hết nhóm tài liệu. Bot sẽ từ chối mọi câu hỏi."
                : "Bot đọc được " + slugs.size() + " nhóm tài liệu.");
    }

    public record ChannelRequest(@NotBlank String teamAadGroupId, String channelId) {
    }

    /**
     * Bind the bot to a Microsoft Teams team. The team's aadGroupId is an Entra group id,
     * so requiring the caller to be a member of it is a real check, not a formality: it
     * stops an owner from installing their bot into another department's team.
     */
    @PostMapping("/bots/{id}/channels")
    public Map<String, Object> bindChannel(@PathVariable long id,
                                           @RequestBody ChannelRequest request) {
        AccessScope scope = CurrentScope.get();
        delegation.requireBot(scope, id);

        String team = request.teamAadGroupId() == null ? "" : request.teamAadGroupId().strip();
        if (!scope.isAdmin() && !scope.entraGroups().contains(team.toLowerCase())) {
            throw new AccessDeniedException("Bạn chỉ gán được bot cho Team mà bạn là thành viên. "
                    + "Lấy aadGroupId của Team trong Teams, và nó phải là Team của bạn.");
        }
        repository.bindChannel(id, team, request.channelId(), scope.displayId());
        platform.refresh();
        return Map.of("message", "Đã gán bot cho Team " + team + ".");
    }

    @DeleteMapping("/bots/channels/{bindingId}")
    public Map<String, Object> unbindChannel(@PathVariable long bindingId) {
        AccessScope scope = CurrentScope.get();
        ChannelBinding binding = platform.snapshot().bindings().stream()
                .filter(b -> b.id() != null && b.id() == bindingId)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Khong tim thay lien ket id=" + bindingId));
        delegation.requireBot(scope, binding.botId() == null ? -1 : binding.botId());

        repository.unbindChannel(bindingId);
        platform.refresh();
        return Map.of("message", "Đã bỏ gán. Team này sẽ dùng bot mặc định.");
    }

    // ------------------------------------------------------------------ helpers

    private CollectionDef collectionById(long id) {
        return platform.snapshot().collections().stream()
                .filter(c -> c.id() != null && c.id() == id)
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Khong tim thay nhom tai lieu id=" + id));
    }

    private Map<String, String> groupNameLookup(DelegationService.Ownership own) {
        GraphDirectoryClient client = graph.getIfAvailable();
        if (client == null) return Map.of();
        Set<String> ids = new LinkedHashSet<>();
        for (CollectionDef c : platform.snapshot().collections()) {
            if (c.id() != null && own.collectionIds().contains(c.id())) ids.addAll(c.aclGroups());
        }
        return ids.isEmpty() ? Map.of() : client.groupNames(ids);
    }

    private List<String> groupNamesFor(List<String> ids) {
        GraphDirectoryClient client = graph.getIfAvailable();
        Map<String, String> lookup = client == null ? Map.of() : client.groupNames(ids);
        List<String> names = new ArrayList<>();
        for (String id : ids) names.add(lookup.getOrDefault(id.toLowerCase(), null));
        return names;
    }

    private static boolean isUuid(String v) {
        return v != null && v.matches("[0-9a-fA-F-]{36}");
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static Map<String, Object> toMap(DocumentMeta m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", m.id());
        out.put("fileName", m.fileName());
        out.put("title", m.title());
        out.put("category", m.category());
        out.put("sourceFormat", m.sourceFormat());
        out.put("chunkCount", m.chunkCount());
        out.put("updatedAt", m.updatedAt());
        return out;
    }
}
