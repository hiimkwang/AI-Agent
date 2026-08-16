package com.ai.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cau hinh dang nhap bang tai khoan cong ty: {@code rag.entra.*}.
 *
 * MAC DINH TAT ({@code enabled=false}) - khi tat, he thong xac thuc y nhu truoc:
 * chi bang API key. Bat len thi giao dien web chuyen sang dang nhap Entra ID, con
 * API key VAN GIU NGUYEN cho may goi may (webhook, script, tac vu nen).
 *
 * Hai duong xac thuc cung ton tai la co y: bot Teams va cac job nen khong co phien
 * trinh duyet, khong the dung OIDC authorization code.
 */
@Component
@ConfigurationProperties(prefix = "rag.entra")
@Getter
@Setter
public class EntraProperties {

    /** Bat/tat toan bo duong dang nhap Entra. Tat = chi con API key. */
    private boolean enabled = false;

    /**
     * Id cua ClientRegistration trong {@code spring.security.oauth2.client.registration.*}.
     * Quyet dinh luon duong redirect: {@code /login/oauth2/code/<registrationId>}.
     */
    private String registrationId = "entra";

    /** Directory (tenant) ID. Dung cho ca kiem tra claim {@code tid} va cho Graph. */
    private String tenantId = "";

    /**
     * Application (client) ID va secret cua app registration.
     *
     * Trung voi {@code spring.security.oauth2.client.registration.entra.*} - cung mot
     * app registration, nhung Graph client can doc truc tiep nen khai bao lai o day
     * (cung tro ve mot bien moi truong, khong nhan ban bi mat).
     */
    private String clientId = "";
    private String clientSecret = "";

    /**
     * Chi cho phep dang nhap neu duoi mien nay. Dai an toan THU HAI - lop thu nhat la
     * app registration dang SingleTenant. Rong = khong kiem tra mien.
     */
    private List<String> allowedEmailDomains = new ArrayList<>(List.of("bsc.com.vn"));

    /**
     * App role cua Entra -> role noi bo (ADMIN/USER).
     *
     * Khoa la ten app role khai bao trong app registration. Vi du:
     * {@code rag.entra.role-mappings.RagAdmin=ADMIN,USER}
     */
    private Map<String, String> roleMappings = new LinkedHashMap<>();

    /** Role gan cho moi nguoi dang nhap hop le, ke ca khi chua co app role nao. */
    private String defaultRoles = "USER";

    /**
     * Duong THAY THE app role: thanh vien cac nhom Entra nay duoc coi la ADMIN.
     * Dung khi to chuc thich quan ly bang nhom hon la app role. Danh sach objectId.
     */
    private List<String> adminGroups = new ArrayList<>();

    /**
     * CUA HAU KHOI DONG: cac UPN duoc cap ADMIN bat ke app role/nhom.
     *
     * Can thiet vi vong ga-va-trung: chua vao duoc {@code /admin.html} thi khong cau
     * hinh duoc gi, ma muon vao thi phai co app role - va app role lai do IT gan.
     * Sau khi da gan app role xong thi NEN XOA danh sach nay.
     */
    private List<String> bootstrapAdminUpns = new ArrayList<>();

    /**
     * Nhom Entra -> danh sach phong ban duoc doc (khop cot {@code category}).
     *
     * Khoa la objectId cua nhom nen co dau gach ngang => PHAI dung cu phap ngoac vuong:
     * {@code rag.entra.group-departments[8f4e...-...]=nhan-su,ke-toan}
     *
     * Nguoi dung khong khop nhom nao va khong phai ADMIN thi khong doc duoc tai lieu
     * nao - mac dinh TU CHOI, giong nguyen tac cua SecurityConfig.
     *
     * P3 se thay {@code category} bang collection; khi do bang nay chuyen thanh
     * rag_collection_acl trong DB. Giai doan nay giu o config cho don gian.
     */
    private Map<String, String> groupDepartments = new LinkedHashMap<>();

    /**
     * Nguoi dang nhap khong khop nhom nao trong {@link #groupDepartments} thi doc duoc
     * gi. Rong = khong doc duoc gi (an toan). Dat {@code *} de mo toan bo - chi nen
     * dung trong giai doan chay thu.
     */
    private String fallbackDepartments = "";

    /** Goi Microsoft Graph de lay nhom + thong tin nguoi dung. Tat = chi dung claim trong token. */
    private boolean graphEnabled = true;

    /**
     * Thoi gian cache thanh vien nhom.
     *
     * Bat buoc phai co cache, khong phai toi uu: khong cache thi moi request ton mot
     * round-trip Graph, va Graph co throttling. Nguoc lai de qua dai thi nguoi chuyen
     * phong van giu quyen cu - 15 phut la diem can bang.
     */
    private int groupCacheMinutes = 15;

    private int graphTimeoutSeconds = 10;

    /** Nen tao phien dang nhap moi cho request bi 401 (browser) hay tra JSON (API client). */
    private String loginPath = "/oauth2/authorization/";

    /** Duong dan day du de bat dau dang nhap. */
    public String authorizationUri() {
        return loginPath + registrationId;
    }

    public boolean hasGraphCredentials() {
        return !tenantId.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
    }
}
