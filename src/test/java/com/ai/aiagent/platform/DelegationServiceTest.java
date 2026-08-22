package com.ai.aiagent.platform;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import com.ai.aiagent.platform.PlatformModels.Grant;
import com.ai.aiagent.security.AccessScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DelegationServiceTest {

    private static final String PHAP_CHE_USER = "11111111-1111-1111-1111-111111111111";
    private static final String NHOM_PHAP_CHE = "22222222-2222-2222-2222-222222222222";
    private static final String NHOM_TOAN_CTY = "33333333-3333-3333-3333-333333333333";
    private static final String NHOM_PHONG_KHAC = "44444444-4444-4444-4444-444444444444";

    private PlatformRepository repository;
    private PlatformService platform;
    private RagProperties props;
    private DelegationService delegation;

    private static CollectionDef collection(long id, String slug) {
        return new CollectionDef(id, slug, slug, null, false, "ACTIVE", Set.of(), 0);
    }

    private static Grant grant(String principalType, String principalId,
                               String scopeType, long scopeId, String role) {
        return new Grant(1L, principalType, principalId, scopeType, scopeId, role, null);
    }

    private static AccessScope entraUser(String objectId, Set<String> groups) {
        return new AccessScope(objectId, "abc@bsc.com.vn", Set.of("USER"), Set.of(), false, groups);
    }

    @BeforeEach
    void setUp() {
        repository = mock(PlatformRepository.class);
        platform = mock(PlatformService.class);
        props = new RagProperties();

        when(repository.grants()).thenReturn(List.of());
        when(repository.namespaceGrants()).thenReturn(List.of());
        when(platform.snapshot()).thenReturn(new PlatformService.Snapshot(
                List.of(collection(10L, "phap-che"), collection(20L, "ptpm")),
                List.of(), List.of()));
        delegation = new DelegationService(repository, platform, props);
    }

    private void withGrants(Grant... grants) {
        when(repository.grants()).thenReturn(List.of(grants));
        delegation.refresh();
    }

    @Test
    @DisplayName("Khong co grant nao => khong quan ly duoc gi")
    void nothingWithoutGrants() {
        AccessScope scope = entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE));
        assertTrue(delegation.ownershipOf(scope).ownsNothing());
        assertThrows(Exception.class, () -> delegation.requireCollection(scope, 10L));
    }

    @Test
    @DisplayName("Cap quyen cho DUNG nguoi => chi quan ly nhom tai lieu duoc giao")
    void userGrantGivesExactlyOneCollection() {
        withGrants(grant("USER", PHAP_CHE_USER, "COLLECTION", 10L, "OWNER"));
        AccessScope scope = entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE));

        DelegationService.Ownership own = delegation.ownershipOf(scope);
        assertEquals(Set.of(10L), own.collectionIds());
        assertEquals(Set.of("phap-che"), own.collectionSlugs());

        delegation.requireCollection(scope, 10L);                         // khong nem
        assertThrows(Exception.class, () -> delegation.requireCollection(scope, 20L));
        assertThrows(Exception.class, () -> delegation.requireSlug(scope, "ptpm"));
    }

    @Test
    @DisplayName("Cap quyen cho NHOM => moi thanh vien nhom do quan ly duoc")
    void groupGrantAppliesToMembers() {
        withGrants(grant("GROUP", NHOM_PHAP_CHE, "COLLECTION", 10L, "OWNER"));

        assertTrue(delegation.ownershipOf(entraUser("x", Set.of(NHOM_PHAP_CHE)))
                .collectionIds().contains(10L));
        assertFalse(delegation.ownershipOf(entraUser("y", Set.of(NHOM_PHONG_KHAC)))
                .collectionIds().contains(10L));
    }

    @Test
    @DisplayName("VIEWER khong duoc sua - chi OWNER va EDITOR")
    void viewerCannotManage() {
        withGrants(grant("USER", PHAP_CHE_USER, "COLLECTION", 10L, "VIEWER"));
        AccessScope scope = entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE));
        assertTrue(delegation.ownershipOf(scope).ownsNothing());
    }

    @Test
    @DisplayName("API key khong bao gio khop grant USER (clientId khong phai uuid)")
    void apiKeyClientNeverMatches() {
        withGrants(grant("USER", PHAP_CHE_USER, "COLLECTION", 10L, "OWNER"));
        AccessScope apiKey = new AccessScope("staff", Set.of("USER"), Set.of(), true);
        assertTrue(delegation.ownershipOf(apiKey).ownsNothing());
    }

    @Test
    @DisplayName("LEO THANG QUYEN: chi cap doc duoc cho nhom minh thuoc")
    void cannotShareWithGroupsYouAreNotIn() {
        withGrants(grant("USER", PHAP_CHE_USER, "COLLECTION", 10L, "OWNER"));
        AccessScope scope = entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE));

        delegation.requireAclTargets(scope, List.of(NHOM_PHAP_CHE));      // hop le
        assertThrows(Exception.class,
                () -> delegation.requireAclTargets(scope, List.of(NHOM_PHONG_KHAC)));
        assertThrows(Exception.class,
                () -> delegation.requireAclTargets(scope,
                        List.of(NHOM_PHAP_CHE, NHOM_PHONG_KHAC)));
    }

    @Test
    @DisplayName("LEO THANG QUYEN: nhom bi chan thi ke ca minh thuoc cung khong duoc dung")
    void deniedGroupIsRefusedEvenForMembers() {
        props.getGrants().setAclDeniedGroups(new java.util.ArrayList<>(List.of(NHOM_TOAN_CTY)));
        withGrants(grant("USER", PHAP_CHE_USER, "COLLECTION", 10L, "OWNER"));

        // Nguoi dung thuoc CA nhom toan cong ty - van bi tu choi.
        AccessScope scope = entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE, NHOM_TOAN_CTY));
        assertFalse(delegation.allowedAclTargets(scope).contains(NHOM_TOAN_CTY));
        assertThrows(Exception.class,
                () -> delegation.requireAclTargets(scope, List.of(NHOM_TOAN_CTY)));
    }

    @Test
    @DisplayName("Nhom bi chan ap dung ca voi ADMIN - de tranh mo toang do nham tay")
    void deniedGroupAlsoBindsAdmins() {
        props.getGrants().setAclDeniedGroups(new java.util.ArrayList<>(List.of(NHOM_TOAN_CTY)));
        AccessScope admin = new AccessScope("adm", "adm@bsc.com.vn", Set.of("ADMIN", "USER"),
                Set.of(), true, Set.of());

        delegation.requireAclTargets(admin, List.of(NHOM_PHONG_KHAC));    // admin tu do
        assertThrows(Exception.class,
                () -> delegation.requireAclTargets(admin, List.of(NHOM_TOAN_CTY)));
    }

    @Test
    @DisplayName("Tat rag.grants.enabled thi moi uy quyen ngung hieu luc")
    void disablingKillsAllDelegation() {
        withGrants(grant("USER", PHAP_CHE_USER, "COLLECTION", 10L, "OWNER"));
        props.getGrants().setEnabled(false);
        assertTrue(delegation.ownershipOf(entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE)))
                .ownsNothing());
    }

    @Test
    @DisplayName("ADMIN quan ly moi nhom tai lieu ma khong can grant")
    void adminOwnsEverything() {
        AccessScope admin = new AccessScope("adm", "adm@bsc.com.vn", Set.of("ADMIN", "USER"),
                Set.of(), true, Set.of());
        DelegationService.Ownership own = delegation.ownershipOf(admin);
        assertTrue(own.admin());
        assertEquals(Set.of(10L, 20L), own.collectionIds());
        assertFalse(own.ownsNothing());
    }

    // ------------------------------------------------------- tien to (namespace)

    private void withNamespaces(PlatformModels.NamespaceGrant... list) {
        when(repository.namespaceGrants()).thenReturn(List.of(list));
        delegation.refresh();
    }

    private static PlatformModels.NamespaceGrant ns(String principalId, String prefix, int max) {
        return new PlatformModels.NamespaceGrant(1L, "USER", principalId, prefix, max, null);
    }

    @Test
    @DisplayName("Tien to: tao duoc dung trong tien to duoc cap")
    void namespaceAllowsOwnPrefixOnly() {
        withNamespaces(ns(PHAP_CHE_USER, "phap-che", 20));
        AccessScope scope = entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE));

        delegation.requireNamespace(scope, "phap-che", 0);
        delegation.requireNamespace(scope, "phap-che-hop-dong", 0);
        assertThrows(Exception.class, () -> delegation.requireNamespace(scope, "nhan-su", 0));
        // "phap-chexyz" khong phai con cua "phap-che" - phai co dau gach noi.
        assertThrows(Exception.class, () -> delegation.requireNamespace(scope, "phap-chexyz", 0));
    }

    @Test
    @DisplayName("Tien to: khong co tien to nao thi khong tao duoc nhom nao")
    void noNamespaceMeansNoCreation() {
        AccessScope scope = entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE));
        assertTrue(delegation.namespacesOf(scope).isEmpty());
        assertThrows(Exception.class, () -> delegation.requireNamespace(scope, "phap-che", 0));
    }

    @Test
    @DisplayName("Tien to: han muc la chan that")
    void namespaceQuotaIsEnforced() {
        withNamespaces(ns(PHAP_CHE_USER, "phap-che", 2));
        AccessScope scope = entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE));

        delegation.requireNamespace(scope, "phap-che-a", 1);
        assertThrows(Exception.class, () -> delegation.requireNamespace(scope, "phap-che-c", 2));
    }

    @Test
    @DisplayName("Tien to: ADMIN dat ma nhom tuy y")
    void adminIgnoresNamespace() {
        AccessScope admin = new AccessScope("adm", "adm@bsc.com.vn", Set.of("ADMIN", "USER"),
                Set.of(), true, Set.of());
        delegation.requireNamespace(admin, "bat-ky-ten-gi", 9999);
    }

    @Test
    @DisplayName("Grant BOT tach roi grant COLLECTION")
    void botGrantIsSeparate() {
        withGrants(grant("USER", PHAP_CHE_USER, "BOT", 77L, "OWNER"));
        AccessScope scope = entraUser(PHAP_CHE_USER, Set.of(NHOM_PHAP_CHE));

        delegation.requireBot(scope, 77L);
        assertThrows(Exception.class, () -> delegation.requireBot(scope, 78L));
        assertTrue(delegation.ownershipOf(scope).collectionIds().isEmpty());
    }
}
