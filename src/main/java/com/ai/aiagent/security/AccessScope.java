package com.ai.aiagent.security;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pham vi truy cap cua mot request: client nao, role gi, duoc doc phong ban nao.
 *
 * Truoc day {@code category} do CLIENT TU KHAI trong body request, nghia la bat ky
 * ai cung doc duoc tai lieu cua moi phong ban. Gio {@code category} chi con la
 * BO LOC THU HEP nam trong pham vi ma API key that su duoc phep - xem
 * {@link #narrowTo(String)}.
 */
public record AccessScope(
        String clientId,
        Set<String> roles,
        Set<String> departments,
        boolean allDepartments
) {

    public static AccessScope internal() {
        return new AccessScope("internal", Set.of("ADMIN", "USER"), Set.of(), true);
    }

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }

    /**
     * Ket hop bo loc category do client yeu cau voi pham vi thuc su duoc phep.
     *
     * @return danh sach category duoc phep tim; rong nghia la "khong gioi han"
     * @throws org.springframework.security.access.AccessDeniedException neu client
     *         yeu cau mot category ngoai pham vi cua no
     */
    public Set<String> narrowTo(String requestedCategory) {
        if (requestedCategory == null || requestedCategory.isBlank()) {
            return allDepartments ? Set.of() : new LinkedHashSet<>(departments);
        }
        String wanted = requestedCategory.trim().toLowerCase();
        if (allDepartments || departments.contains(wanted)) {
            return Set.of(wanted);
        }
        throw new org.springframework.security.access.AccessDeniedException(
                "Khong co quyen truy cap nhom tai lieu '" + wanted + "'.");
    }

    /** Chuoi dinh danh pham vi, dung lam phan cua cache key de khong lan du lieu giua cac quyen. */
    public String cacheScopeKey() {
        if (allDepartments) return "all";
        return String.join(",", new java.util.TreeSet<>(departments));
    }

    /** Literal mang Postgres cho role, dung cho dieu kien {@code allowed_roles && ?::text[]}. */
    public String rolesArrayLiteral() {
        return "{" + String.join(",", roles) + "}";
    }

    /** Literal mang Postgres cho phong ban duoc phep. */
    public String departmentsArrayLiteral(Set<String> effective) {
        return "{" + String.join(",", effective) + "}";
    }
}
