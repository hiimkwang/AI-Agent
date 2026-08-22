package com.ai.aiagent.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccessScopeTest {

    private static AccessScope user(Set<String> departments) {
        return new AccessScope("u", Set.of("USER"), departments, false);
    }

    @Test
    @DisplayName("Hai phong ban khac nhau khong duoc dung chung o cache")
    void differentDepartmentsGetDifferentCacheKeys() {
        assertNotEquals(user(Set.of("nhan-su")).cacheScopeKey(),
                user(Set.of("ke-toan")).cacheScopeKey());
    }

    @Test
    @DisplayName("Thu tu phong ban khong lam doi khoa cache")
    void departmentOrderDoesNotMatter() {
        assertEquals(user(Set.of("nhan-su", "ke-toan")).cacheScopeKey(),
                user(Set.of("ke-toan", "nhan-su")).cacheScopeKey());
    }

    @Test
    @DisplayName("ADMIN va USER cung phong ban KHONG duoc dung chung o cache")
    void adminAndUserDoNotShareCache() {
        AccessScope admin = new AccessScope("a", Set.of("ADMIN", "USER"), Set.of("nhan-su"), false);
        AccessScope plain = user(Set.of("nhan-su"));
        assertNotEquals(admin.cacheScopeKey(), plain.cacheScopeKey());
    }

    @Test
    @DisplayName("Moi ADMIN dung chung mot o cache - phan biet them chi lam bam nho cache vo ich")
    void adminsShareOneBucket() {
        AccessScope a = new AccessScope("a", Set.of("ADMIN", "USER"), Set.of(), true);
        AccessScope b = new AccessScope("b", Set.of("ADMIN"), Set.of(), true);
        assertEquals(a.cacheScopeKey(), b.cacheScopeKey());
    }

    @Test
    @DisplayName("narrowTo: khong co category yeu cau thi tra ve dung pham vi duoc phep")
    void narrowToWithoutRequestedCategory() {
        assertEquals(Set.of(), new AccessScope("a", Set.of("USER"), Set.of(), true).narrowTo(null));
        assertEquals(Set.of("nhan-su"), user(Set.of("nhan-su")).narrowTo("  "));
    }

    @Test
    @DisplayName("narrowTo: xin category ngoai pham vi thi bi tu choi, khong am tham mo rong")
    void narrowToRejectsOutOfScope() {
        assertThrows(AccessDeniedException.class, () -> user(Set.of("nhan-su")).narrowTo("ke-toan"));
        assertEquals(Set.of("nhan-su"), user(Set.of("nhan-su")).narrowTo("NHAN-SU"));
    }

    @Test
    @DisplayName("Duong API key khong co UPN nen displayId lui ve clientId")
    void displayIdFallsBackToClientId() {
        assertEquals("admin", new AccessScope("admin", Set.of("USER"), Set.of(), true).displayId());
        assertEquals("a@bsc.com.vn", new AccessScope("oid-1", "a@bsc.com.vn", Set.of("USER"),
                Set.of(), true, Set.of()).displayId());
    }

    @Test
    @DisplayName("Constructor rut gon giu nguyen hanh vi cu cho duong API key")
    void compactConstructorKeepsApiKeyBehaviour() {
        AccessScope scope = new AccessScope("staff", Set.of("USER"), Set.of("nhan-su"), false);
        assertTrue(scope.entraGroups().isEmpty());
        assertEquals(null, scope.upn());
    }
}
