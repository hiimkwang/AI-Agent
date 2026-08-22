package com.ai.aiagent.security;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

public record AccessScope(
        String clientId,
        String upn,
        Set<String> roles,
        Set<String> departments,
        boolean allDepartments,
        Set<String> entraGroups
) {

    public AccessScope(String clientId, Set<String> roles, Set<String> departments,
                       boolean allDepartments) {
        this(clientId, null, roles, departments, allDepartments, Set.of());
    }

    public static AccessScope internal() {
        return new AccessScope("internal", Set.of("ADMIN", "USER"), Set.of(), true);
    }

    public boolean isAdmin() {
        return roles.contains("ADMIN");
    }

    public String displayId() {
        return upn == null || upn.isBlank() ? clientId : upn;
    }

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

    // Must include roles, not just departments: HybridRetriever skips the
    // allowed_roles filter for admins, so a key without roles would serve an
    // admin's cached answer to a regular user.
    public String cacheScopeKey() {
        String deps = allDepartments ? "all" : String.join(",", new TreeSet<>(departments));
        String rolePart = isAdmin() ? "admin" : String.join(",", new TreeSet<>(roles));
        return deps + "|r=" + rolePart;
    }

    public String rolesArrayLiteral() {
        return "{" + String.join(",", roles) + "}";
    }

    public String departmentsArrayLiteral(Set<String> effective) {
        return "{" + String.join(",", effective) + "}";
    }
}
