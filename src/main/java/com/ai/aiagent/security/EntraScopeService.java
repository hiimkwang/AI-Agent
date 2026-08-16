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

/**
 * Dich danh tinh Entra ID thanh {@link AccessScope}.
 *
 * Day la DIEM DUY NHAT bien "anh A, app role RagEditor, thuoc nhom X va Y" thanh
 * "duoc doc phong ban nhan-su, ke-toan". Ca duong web (P1) va bot Teams (P2) deu goi
 * vao day, de khong bao gio co hai cach tinh quyen lech nhau.
 *
 * Nguyen tac: MAC DINH TU CHOI. Nguoi khong khop nhom nao va khong phai ADMIN thi
 * khong doc duoc tai lieu nao - giong nguyen tac cua {@link SecurityConfig}. Cu the la
 * khi Graph loi, {@link GraphDirectoryClient#memberGroups} tra ve rong, va rong o day
 * nghia la "khong co quyen gi" chu KHONG phai "co moi quyen".
 */
@Service
@ConditionalOnProperty(prefix = "rag.entra", name = "enabled", havingValue = "true")
@Slf4j
public class EntraScopeService {

    private final EntraProperties props;
    private final GraphDirectoryClient graph;
    private final UserRepository users;
    private final PlatformService platform;

    /**
     * Cache thanh vien nhom - phan DUY NHAT ton mot round-trip mang.
     *
     * Co y chi cache phan nay, khong cache ca {@link AccessScope}: role lay tu token nen
     * luon tuoi, con nhom thi dat. Cache ca scope se lam role bi dong bang theo phien.
     */
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

    /** Nhom Entra (transitive) cua nguoi dung, co cache. */
    public Set<String> groupsOf(String objectId) {
        if (objectId == null || objectId.isBlank()) return Set.of();
        return groupCache.get(objectId.toLowerCase(), id -> graph.memberGroups(objectId));
    }

    public void invalidate(String objectId) {
        if (objectId != null) groupCache.invalidate(objectId.toLowerCase());
    }

    /**
     * Dung pham vi truy cap cho mot nguoi dung da xac thuc.
     *
     * @param objectId claim {@code oid} trong token Entra - dinh danh ben vung, khong doi
     *                 khi nguoi dung doi ten hay doi email
     * @param upn      {@code preferred_username}, chi de log/hien thi
     * @param appRoles claim {@code roles} - app role da gan trong app registration;
     *                 rong khi to chuc chua khai bao app role nao
     */
    public AccessScope scopeOf(String objectId, String upn, Collection<String> appRoles) {
        Set<String> groups = groupsOf(objectId);
        Set<String> roles = resolveRoles(upn, appRoles, groups);
        boolean admin = roles.contains("ADMIN");

        // ADMIN doc moi phong ban; khong can tinh giao voi nhom.
        if (admin) {
            return new AccessScope(objectId, upn, roles, Set.of(), true, groups);
        }

        Set<String> departments = resolveDepartments(groups);
        if (departments.contains("*")) {
            return new AccessScope(objectId, upn, roles, Set.of(), true, groups);
        }
        if (departments.isEmpty()) {
            log.debug("Nguoi dung {} khong khop nhom nao trong rag.entra.group-departments "
                    + "-> khong doc duoc tai lieu nao.", upn);
        }
        return new AccessScope(objectId, upn, roles, departments, false, groups);
    }

    /** Goi mot lan luc dang nhap: lay ho so day du va ghi vao {@code rag_users} de audit. */
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

    // ============================================================ Noi bo

    /**
     * Ba duong cap role, cong don:
     *   1) app role trong token, dich qua {@code rag.entra.role-mappings}
     *   2) thanh vien {@code rag.entra.admin-groups} - danh cho to chuc quan ly bang nhom
     *   3) {@code rag.entra.bootstrap-admin-upns} - cua hau de pha vong ga-va-trung
     * Cuoi cung luon cong {@code default-roles} de nguoi dang nhap hop le it nhat la USER.
     */
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
                    log.debug("App role '{}' khong co trong rag.entra.role-mappings -> bo qua.",
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
            log.warn("Cap ADMIN cho {} qua rag.entra.bootstrap-admin-upns. "
                    + "XOA cau hinh nay sau khi da gan app role trong Entra.", upn);
            roles.add("ADMIN");
            roles.add("USER");
        }

        roles.addAll(split(props.getDefaultRoles(), true));
        return roles;
    }

    /**
     * Cac tap tai lieu nguoi dung doc duoc.
     *
     * MOT nguon su that tai mot thoi diem, khong phai hai:
     *   - Da co ACL trong bang {@code rag_collection_acl} (P3) => chi dung DB.
     *   - Chua co dong ACL nao => lui ve {@code rag.entra.group-departments} (P1).
     *
     * Co y khong HOP hai nguon. Voi mot co che phan quyen, hai nguon cong don nghia la
     * khi kiem tra "vi sao anh A doc duoc tai lieu nay" phai tra o hai cho, va go quyen
     * o mot cho khong co tac dung. Chuyen giao tu dong nhu tren giu duoc cau hinh P1
     * dang chay ma khong tao ra su nhap nhang do.
     */
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

    /**
     * @param upper true cho ROLE (quy uoc Spring Security la chu hoa), false cho phong ban
     *              (chuan hoa ve chu thuong de khop du lieu dang "nhan-su")
     */
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
