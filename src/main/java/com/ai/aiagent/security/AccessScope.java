package com.ai.aiagent.security;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pham vi truy cap cua mot request: ai goi, role gi, duoc doc phong ban nao.
 *
 * Truoc day {@code category} do CLIENT TU KHAI trong body request, nghia la bat ky
 * ai cung doc duoc tai lieu cua moi phong ban. Gio {@code category} chi con la
 * BO LOC THU HEP nam trong pham vi ma nguoi goi that su duoc phep - xem
 * {@link #narrowTo(String)}.
 *
 * Mot record duy nhat cho CA HAI duong xac thuc (API key va Entra ID) la co y:
 * {@code ChunkRepository} khong duoc phai biet request den tu trinh duyet, tu Teams
 * hay tu script. Hai duong phan quyen khac nhau la cong thuc chac chan dan den ro ri.
 *
 * @param clientId    dinh danh nguoi goi: id cua API key, hoac objectId nguoi dung Entra
 * @param upn         {@code a@bsc.com.vn} - chi de log/audit, KHONG dung phan quyen;
 *                    null voi duong API key
 * @param entraGroups objectId (chu thuong) cac nhom Entra cua nguoi dung; rong voi API key
 */
public record AccessScope(
        String clientId,
        String upn,
        Set<String> roles,
        Set<String> departments,
        boolean allDepartments,
        Set<String> entraGroups
) {

    /** Dung cho duong API key va cac tac vu noi bo - khong co danh tinh nguoi dung. */
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

    /** Ten de hien thi trong log va giao dien: uu tien UPN vi de truy nguoc hon id. */
    public String displayId() {
        return upn == null || upn.isBlank() ? clientId : upn;
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

    /**
     * Chuoi dinh danh pham vi, dung lam phan cua cache key de khong lan du lieu giua
     * cac quyen khac nhau.
     *
     * PHAI gom CA phan role, khong chi phong ban. Ly do: {@code HybridRetriever} loc
     * theo {@code allowed_roles} bang {@code scope.isAdmin() ? Set.of() : scope.roles()},
     * nghia la ADMIN nhin thay ca tai lieu han che. Neu khoa cache chi gom phong ban
     * thi hai nguoi cung phong nhung khac role dung chung mot o cache => cau tra loi
     * sinh cho ADMIN duoc phuc vu lai cho USER. Cache semantic (cosine >= 0.97) lam
     * ro ri nay nang hon vi khong can cau hoi giong het.
     *
     * Chi ghi "admin" thay vi liet ke role, de khong bam nho cache vo ich: dung mot
     * dieu kien PHAN BIET duy nhat ma cau SQL thuc su dung.
     */
    public String cacheScopeKey() {
        String deps = allDepartments ? "all" : String.join(",", new TreeSet<>(departments));
        String rolePart = isAdmin() ? "admin" : String.join(",", new TreeSet<>(roles));
        return deps + "|r=" + rolePart;
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
