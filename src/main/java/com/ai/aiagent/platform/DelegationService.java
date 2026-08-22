package com.ai.aiagent.platform;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import com.ai.aiagent.platform.PlatformModels.Grant;
import com.ai.aiagent.platform.PlatformModels.NamespaceGrant;
import com.ai.aiagent.security.AccessScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns rows in {@code rag_grants} into actual authorisation decisions.
 * <p>
 * The table and its write API existed long before this class; nothing read them, so
 * granting was a no-op that looked like it worked. Every delegated endpoint under
 * {@code /api/v1/rag/my} must go through here.
 */
@Service
@Slf4j
public class DelegationService {

    /** Roles that may change things. VIEWER is deliberately excluded. */
    private static final Set<String> MANAGING_ROLES = Set.of("OWNER", "EDITOR");

    public record Ownership(Set<Long> collectionIds, Set<String> collectionSlugs,
                            Set<Long> botIds, boolean admin) {

        public boolean ownsNothing() {
            return !admin && collectionIds.isEmpty() && botIds.isEmpty();
        }
    }

    private final PlatformRepository repository;
    private final PlatformService platform;
    private final RagProperties props;

    private volatile List<Grant> grants = List.of();
    private volatile List<NamespaceGrant> namespaces = List.of();

    public DelegationService(PlatformRepository repository, PlatformService platform,
                             RagProperties props) {
        this.repository = repository;
        this.platform = platform;
        this.props = props;
        refreshQuietly();
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void refreshScheduled() {
        refreshQuietly();
    }

    public void refresh() {
        this.grants = List.copyOf(repository.grants());
        this.namespaces = List.copyOf(repository.namespaceGrants());
    }

    // ------------------------------------------------------- namespace (tien to slug)

    /** Slug prefixes this caller may create collections under. */
    public List<NamespaceGrant> namespacesOf(AccessScope scope) {
        if (!enabled() || scope == null) return List.of();
        List<NamespaceGrant> out = new ArrayList<>();
        for (NamespaceGrant n : namespaces) {
            if (matchesPrincipal(n.principalType(), n.principalId(), scope)) out.add(n);
        }
        return out;
    }

    /**
     * An admin may create any slug. A delegated owner may only create inside a prefix
     * they were given, and only up to the quota on that prefix - without a cap one person
     * could create thousands of collections and make the category list useless.
     */
    public void requireNamespace(AccessScope scope, String slug, long existingUnderPrefix) {
        if (scope != null && scope.isAdmin()) return;

        String wanted = norm(slug);
        for (NamespaceGrant n : namespacesOf(scope)) {
            if (!n.covers(wanted)) continue;
            if (existingUnderPrefix >= n.maxCollections()) {
                throw new AccessDeniedException("Đã đạt giới hạn " + n.maxCollections()
                        + " nhóm tài liệu cho tiền tố '" + n.slugPrefix()
                        + "'. Đề nghị quản trị nâng giới hạn.");
            }
            return;
        }
        List<String> prefixes = namespacesOf(scope).stream().map(NamespaceGrant::slugPrefix).toList();
        throw new AccessDeniedException(prefixes.isEmpty()
                ? "Bạn chưa được cấp tiền tố nào để tự tạo nhóm tài liệu."
                : "Mã nhóm phải bắt đầu bằng một trong các tiền tố được cấp: "
                        + String.join(", ", prefixes) + " (ví dụ '" + prefixes.get(0) + "-hop-dong').");
    }

    /** How many collections already exist under the prefix covering this slug. */
    public long countUnderPrefixFor(AccessScope scope, String slug) {
        String wanted = norm(slug);
        return namespacesOf(scope).stream()
                .filter(n -> n.covers(wanted))
                .findFirst()
                .map(n -> platform.snapshot().collections().stream()
                        .filter(c -> n.covers(c.slug()))
                        .count())
                .orElse(0L);
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            // Startup order: the table may not be migrated yet on a fresh database.
            log.warn("Could not load the delegation grants ({}). Retrying in one minute.",
                    e.getMessage());
        }
    }

    public boolean enabled() {
        return props.getGrants().isEnabled();
    }

    public Ownership ownershipOf(AccessScope scope) {
        boolean admin = scope != null && scope.isAdmin();
        Set<Long> collectionIds = new LinkedHashSet<>();
        Set<String> slugs = new LinkedHashSet<>();
        Set<Long> botIds = new LinkedHashSet<>();

        if (admin) {
            for (CollectionDef c : platform.snapshot().collections()) {
                if (c.id() != null) collectionIds.add(c.id());
                slugs.add(c.slug());
            }
            platform.snapshot().bots().forEach(b -> {
                if (b.id() != null) botIds.add(b.id());
            });
            return new Ownership(collectionIds, slugs, botIds, true);
        }

        if (!enabled() || scope == null) {
            return new Ownership(Set.of(), Set.of(), Set.of(), false);
        }

        for (Grant g : grants) {
            if (!MANAGING_ROLES.contains(upper(g.role()))) continue;
            if (!matches(g, scope)) continue;

            if ("COLLECTION".equalsIgnoreCase(g.scopeType())) {
                collectionIds.add(g.scopeId());
                platform.snapshot().collections().stream()
                        .filter(c -> c.id() != null && c.id() == g.scopeId())
                        .findFirst()
                        .ifPresent(c -> slugs.add(c.slug()));
            } else if ("BOT".equalsIgnoreCase(g.scopeType())) {
                botIds.add(g.scopeId());
            }
        }
        return new Ownership(collectionIds, slugs, botIds, false);
    }

    private static boolean matches(Grant g, AccessScope scope) {
        return matchesPrincipal(g.principalType(), g.principalId(), scope);
    }

    private static boolean matchesPrincipal(String principalType, String principalId,
                                            AccessScope scope) {
        String principal = principalId == null ? "" : principalId.strip().toLowerCase();
        if (principal.isEmpty() || scope == null) return false;

        if ("USER".equalsIgnoreCase(principalType)) {
            // Entra puts the object id in clientId; API-key clients use a name, never a uuid.
            String id = scope.clientId() == null ? "" : scope.clientId().strip().toLowerCase();
            return principal.equals(id);
        }
        if ("GROUP".equalsIgnoreCase(principalType)) {
            return scope.entraGroups().contains(principal);
        }
        return false;
    }

    public void requireCollection(AccessScope scope, long collectionId) {
        if (!ownershipOf(scope).collectionIds().contains(collectionId)) {
            throw new AccessDeniedException(
                    "Bạn không được giao quản lý nhóm tài liệu này.");
        }
    }

    public void requireBot(AccessScope scope, long botId) {
        if (!ownershipOf(scope).botIds().contains(botId)) {
            throw new AccessDeniedException("Bạn không được giao quản lý bot này.");
        }
    }

    public void requireSlug(AccessScope scope, String slug) {
        String wanted = slug == null ? "" : slug.strip().toLowerCase();
        if (wanted.isEmpty() || !ownershipOf(scope).collectionSlugs().contains(wanted)) {
            throw new AccessDeniedException(
                    "Bạn không được giao quản lý nhóm tài liệu '" + slug + "'.");
        }
    }

    /**
     * Groups a delegated owner is allowed to grant read access to.
     * <p>
     * Restricted to their own groups on purpose: without that, an owner could share a
     * collection with a company-wide group and publish it to everyone in one click.
     * {@code rag.grants.acl-denied-groups} removes groups that are too broad even so.
     */
    public Set<String> allowedAclTargets(AccessScope scope) {
        if (scope != null && scope.isAdmin()) return Set.of();       // empty = no restriction
        Set<String> out = new LinkedHashSet<>(scope == null ? Set.of() : scope.entraGroups());
        out.removeAll(deniedGroups());
        return out;
    }

    public Set<String> deniedGroups() {
        Set<String> out = new LinkedHashSet<>();
        for (String g : props.getGrants().getAclDeniedGroups()) {
            if (g != null && !g.isBlank()) out.add(g.strip().toLowerCase());
        }
        return out;
    }

    public void requireAclTargets(AccessScope scope, Collection<String> requested) {
        if (requested == null) return;
        if (scope != null && scope.isAdmin()) {
            // An admin may use any group, but never one the deny list forbids outright.
            Set<String> denied = deniedGroups();
            for (String raw : requested) {
                String id = norm(raw);
                if (!id.isEmpty() && denied.contains(id)) {
                    throw new AccessDeniedException("Nhóm " + id
                            + " nằm trong danh sách bị chặn làm quyền đọc (rag.grants.acl-denied-groups).");
                }
            }
            return;
        }
        Set<String> allowed = allowedAclTargets(scope);
        for (String raw : requested) {
            String id = norm(raw);
            if (id.isEmpty()) continue;
            if (!allowed.contains(id)) {
                throw new AccessDeniedException("Bạn chỉ được cấp quyền đọc cho nhóm mà chính "
                        + "bạn là thành viên, và nhóm đó không bị chặn. Nhóm không hợp lệ: " + id);
            }
        }
    }

    private static String norm(String v) {
        return v == null ? "" : v.strip().toLowerCase();
    }

    private static String upper(String v) {
        return v == null ? "" : v.strip().toUpperCase();
    }
}
