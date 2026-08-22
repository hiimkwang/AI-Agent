package com.ai.aiagent.security;

import com.ai.aiagent.config.EntraProperties;
import com.ai.aiagent.platform.PlatformService;
import com.ai.aiagent.store.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
@ConditionalOnProperty(prefix = "rag.entra", name = "enabled", havingValue = "true")
@Slf4j
public class EntraScopeService {

    private final EntraProperties props;
    private final GraphDirectoryClient graph;
    private final UserRepository users;
    private final PlatformService platform;

    private final Cache<String, Set<String>> groupCache;

    public EntraScopeService(EntraProperties props, GraphDirectoryClient graph,
                             UserRepository users, PlatformService platform) {
        this.props = props;
        this.graph = graph;
        this.users = users;
        this.platform = platform;
        this.groupCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(Math.max(1, props.getGroupCacheMinutes())))
                .maximumSize(20_000)
                .build();
    }

    public Set<String> groupsOf(String objectId) {
        if (objectId == null || objectId.isBlank()) return Set.of();
        return groupCache.get(objectId.toLowerCase(), id -> graph.memberGroups(objectId));
    }

    public void invalidate(String objectId) {
        if (objectId != null) groupCache.invalidate(objectId.toLowerCase());
    }

    public AccessScope scopeOf(String objectId, String upn, Collection<String> appRoles) {
        Set<String> groups = groupsOf(objectId);
        Set<String> roles = resolveRoles(upn, appRoles, groups);
        boolean admin = roles.contains("ADMIN");

        if (admin) {
            return new AccessScope(objectId, upn, roles, Set.of(), true, groups);
        }

        Set<String> departments = resolveDepartments(groups);
        if (departments.contains("*")) {
            return new AccessScope(objectId, upn, roles, Set.of(), true, groups);
        }
        if (departments.isEmpty()) {
            log.debug("User {} matches no group in rag.entra.group-departments, so no document "
                    + "is readable.", upn);
        }
        return new AccessScope(objectId, upn, roles, departments, false, groups);
    }

    public void recordLogin(String objectId, String fallbackUpn, String fallbackName) {
        Set<String> groups = groupsOf(objectId);
        GraphDirectoryClient.Profile profile = graph.profile(objectId);
        users.upsert(objectId,
                profile != null && profile.upn() != null ? profile.upn() : fallbackUpn,
                profile != null && profile.displayName() != null ? profile.displayName() : fallbackName,
                profile == null ? null : profile.department(),
                profile == null ? null : profile.jobTitle(),
                groups);
    }

    private Set<String> resolveRoles(String upn, Collection<String> appRoles, Set<String> groups) {
        Set<String> roles = new LinkedHashSet<>();

        if (appRoles != null) {
            Map<String, String> mappings = props.getRoleMappings();
            for (String appRole : appRoles) {
                if (appRole == null || appRole.isBlank()) continue;
                String mapped = lookupIgnoreCase(mappings, appRole.strip());
                if (mapped != null) {
                    roles.addAll(split(mapped, true));
                } else {
                    log.debug("App role '{}' is absent from rag.entra.role-mappings, ignoring it.",
                            appRole);
                }
            }
        }

        Set<String> adminGroups = GraphDirectoryClient.normalizeIds(props.getAdminGroups());
        if (!adminGroups.isEmpty() && groups.stream().anyMatch(adminGroups::contains)) {
            roles.add("ADMIN");
            roles.add("USER");
        }

        if (upn != null && props.getBootstrapAdminUpns().stream()
                .anyMatch(u -> u != null && u.strip().equalsIgnoreCase(upn))) {
            log.warn("Granting ADMIN to {} via rag.entra.bootstrap-admin-upns. REMOVE this setting "
                    + "once the app role is assigned in Entra.", upn);
            roles.add("ADMIN");
            roles.add("USER");
        }

        roles.addAll(split(props.getDefaultRoles(), true));
        return roles;
    }

    private Set<String> resolveDepartments(Set<String> groups) {
        Set<String> departments = platform.hasNoAcl()
                ? fromProperties(groups)
                : platform.readableSlugs(groups);

        if (departments.isEmpty()) {
            departments = split(props.getFallbackDepartments(), false);
        }
        return departments;
    }

    private Set<String> fromProperties(Set<String> groups) {
        Set<String> departments = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : props.getGroupDepartments().entrySet()) {
            String groupId = e.getKey() == null ? "" : e.getKey().strip().toLowerCase();
            if (groups.contains(groupId)) {
                departments.addAll(split(e.getValue(), false));
            }
        }
        return departments;
    }

    private static String lookupIgnoreCase(Map<String, String> map, String key) {
        String direct = map.get(key);
        if (direct != null) return direct;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(key)) return e.getValue();
        }
        return null;
    }

    private static Set<String> split(String csv, boolean upper) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) return out;
        Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> "*".equals(s) ? s : (upper ? s.toUpperCase() : s.toLowerCase()))
                .forEach(out::add);
        return out;
    }
}
