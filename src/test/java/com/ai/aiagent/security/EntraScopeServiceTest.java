package com.ai.aiagent.security;

import com.ai.aiagent.config.EntraProperties;
import com.ai.aiagent.platform.PlatformService;
import com.ai.aiagent.store.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Day la lop dich "anh A thuoc nhom X" thanh "doc duoc phong nhan-su". Sai o day la
 * sai quyen tren toan he thong, nen kiem tra ky ca duong MO lan duong DONG.
 */
class EntraScopeServiceTest {

    private static final String OID = "11111111-1111-1111-1111-111111111111";
    private static final String NHAN_SU = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String KE_TOAN = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String QUAN_TRI = "cccccccc-0000-0000-0000-000000000003";

    private EntraProperties props;
    private GraphDirectoryClient graph;
    private PlatformService platform;
    private EntraScopeService service;

    @BeforeEach
    void setUp() {
        props = new EntraProperties();
        props.setDefaultRoles("USER");
        props.getRoleMappings().put("RagAdmin", "ADMIN,USER");
        props.getRoleMappings().put("RagEditor", "USER");
        props.getGroupDepartments().put(NHAN_SU, "nhan-su");
        props.getGroupDepartments().put(KE_TOAN, "ke-toan,cong-no");
        graph = mock(GraphDirectoryClient.class);
        platform = mock(PlatformService.class);
        // Mac dinh: chua cau hinh ACL trong DB => lui ve rag.entra.group-departments (P1)
        when(platform.hasNoAcl()).thenReturn(true);
        service = new EntraScopeService(props, graph, mock(UserRepository.class), platform);
    }

    private void memberOf(String... groups) {
        when(graph.memberGroups(anyString())).thenReturn(Set.of(groups));
    }

    @Test
    @DisplayName("Nhom Entra mo ra dung phong ban tuong ung, hop lai khi thuoc nhieu nhom")
    void groupsMapToDepartments() {
        memberOf(NHAN_SU, KE_TOAN);
        AccessScope scope = service.scopeOf(OID, "a@bsc.com.vn", List.of());

        assertFalse(scope.allDepartments());
        assertEquals(Set.of("nhan-su", "ke-toan", "cong-no"), scope.departments());
        assertEquals(Set.of("USER"), scope.roles());
    }

    /**
     * MAC DINH TU CHOI. Neu cho ai khong khop nhom nao doc het thi mot nguoi moi vao
     * cong ty, chua duoc gan nhom, se doc duoc toan bo tai lieu noi bo.
     */
    @Test
    @DisplayName("Khong khop nhom nao va khong phai ADMIN => khong doc duoc gi")
    void unknownGroupsGetNothing() {
        memberOf("dddddddd-0000-0000-0000-000000000009");
        AccessScope scope = service.scopeOf(OID, "a@bsc.com.vn", List.of());

        assertFalse(scope.allDepartments());
        assertTrue(scope.departments().isEmpty());
    }

    /**
     * Graph loi thi {@link GraphDirectoryClient#memberGroups} tra ve rong. Rong PHAI
     * nghia la "khong co quyen gi", tuyet doi khong duoc thanh "co moi quyen".
     */
    @Test
    @DisplayName("Graph loi (khong lay duoc nhom) => dong quyen lai, khong mo ra")
    void graphFailureFailsClosed() {
        when(graph.memberGroups(anyString())).thenReturn(Set.of());
        AccessScope scope = service.scopeOf(OID, "a@bsc.com.vn", List.of());

        assertFalse(scope.allDepartments());
        assertTrue(scope.departments().isEmpty());
        assertFalse(scope.isAdmin());
    }

    @Test
    @DisplayName("App role RagAdmin => ADMIN va doc duoc moi phong ban")
    void appRoleGrantsAdmin() {
        memberOf(NHAN_SU);
        AccessScope scope = service.scopeOf(OID, "a@bsc.com.vn", List.of("RagAdmin"));

        assertTrue(scope.isAdmin());
        assertTrue(scope.allDepartments());
    }

    @Test
    @DisplayName("App role khop khong phan biet chu hoa (relaxed binding lam doi chu cua khoa)")
    void appRoleLookupIsCaseInsensitive() {
        memberOf(NHAN_SU);
        assertTrue(service.scopeOf(OID, "a@bsc.com.vn", List.of("ragadmin")).isAdmin());
    }

    @Test
    @DisplayName("App role la de => bo qua, khong nem loi va khong cap quyen")
    void unknownAppRoleIsIgnored() {
        memberOf(NHAN_SU);
        AccessScope scope = service.scopeOf(OID, "a@bsc.com.vn", List.of("SomeOtherApp.Role"));

        assertFalse(scope.isAdmin());
        assertEquals(Set.of("USER"), scope.roles());
    }

    @Test
    @DisplayName("Thanh vien admin-groups => ADMIN, khong can app role")
    void adminGroupGrantsAdmin() {
        props.setAdminGroups(List.of(QUAN_TRI.toUpperCase()));
        memberOf(QUAN_TRI);
        assertTrue(service.scopeOf(OID, "a@bsc.com.vn", List.of()).isAdmin());
    }

    @Test
    @DisplayName("Cua hau bootstrap-admin-upns cap ADMIN khi chua gan app role nao")
    void bootstrapAdminByUpn() {
        props.setBootstrapAdminUpns(List.of("Quang@BSC.com.vn"));
        memberOf(NHAN_SU);
        assertTrue(service.scopeOf(OID, "quang@bsc.com.vn", List.of()).isAdmin());
        assertFalse(service.scopeOf(OID, "nguoikhac@bsc.com.vn", List.of()).isAdmin());
    }

    @Test
    @DisplayName("fallback-departments=* mo toan bo - chi danh cho giai doan chay thu")
    void wildcardFallbackOpensEverything() {
        props.setFallbackDepartments("*");
        memberOf("dddddddd-0000-0000-0000-000000000009");
        AccessScope scope = service.scopeOf(OID, "a@bsc.com.vn", List.of());

        assertTrue(scope.allDepartments());
        assertFalse(scope.isAdmin(), "mo phong ban KHONG duoc keo theo quyen ADMIN");
    }

    @Test
    @DisplayName("Chi goi Graph mot lan cho nhieu request cua cung nguoi dung (cache)")
    void groupsAreCached() {
        memberOf(NHAN_SU);
        service.scopeOf(OID, "a@bsc.com.vn", List.of());
        service.scopeOf(OID, "a@bsc.com.vn", List.of());
        org.mockito.Mockito.verify(graph, org.mockito.Mockito.times(1)).memberGroups(OID);
    }

    /**
     * MOT nguon su that tai mot thoi diem. Khi da co ACL trong DB (P3), cau hinh
     * {@code group-departments} cua P1 phai NGUNG co tac dung - neu khong, go quyen trong
     * DB se khong co hieu luc vi properties van mo, va rat kho phat hien.
     */
    @Test
    @DisplayName("Da co ACL trong DB => dung DB, KHONG cong them cau hinh P1")
    void databaseAclSupersedesProperties() {
        when(platform.hasNoAcl()).thenReturn(false);
        when(platform.readableSlugs(any())).thenReturn(Set.of("phap-che"));
        memberOf(NHAN_SU);

        AccessScope scope = service.scopeOf(OID, "a@bsc.com.vn", List.of());

        assertEquals(Set.of("phap-che"), scope.departments(),
                "nhan-su tu properties khong duoc cong vao khi DB da co ACL");
    }

    @Test
    @DisplayName("Chua co ACL trong DB => van dung cau hinh P1, khong lam hong ban dang chay")
    void fallsBackToPropertiesWhenDatabaseEmpty() {
        when(platform.hasNoAcl()).thenReturn(true);
        memberOf(NHAN_SU);

        assertEquals(Set.of("nhan-su"),
                service.scopeOf(OID, "a@bsc.com.vn", List.of()).departments());
    }

    @Test
    @DisplayName("Nhom cua nguoi dung duoc giu lai trong scope de chan doan quyen")
    void scopeCarriesGroupsForDiagnostics() {
        memberOf(NHAN_SU);
        assertEquals(Set.of(NHAN_SU), service.scopeOf(OID, "a@bsc.com.vn", List.of()).entraGroups());
    }
}
