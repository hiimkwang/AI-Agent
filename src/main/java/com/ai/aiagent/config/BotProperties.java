package com.ai.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Bot Teams that (Azure Bot Service + giao thuc Bot Framework): {@code rag.bot.*}.
 *
 * Khac han {@code rag.teams.*} cu (Outgoing Webhook). Webhook cu chi chay trong dung
 * team da tao no, chi khi bi @mention trong channel, va KHONG co danh tinh nguoi dung
 * - payload khong he co {@code aadObjectId}. Bot nay co ca ba thu do, nen moi phan
 * quyen theo nguoi tren Teams duoc.
 *
 * Mac dinh TAT. Xem docs/TEAMS-BOT-SETUP.md.
 */
@Component
@ConfigurationProperties(prefix = "rag.bot")
@Getter
@Setter
public class BotProperties {

    public enum AppType {
        /** App registration multi-tenant - kieu mac dinh cua Azure Bot Service. */
        MULTI_TENANT,
        /** App registration single-tenant - token do chinh tenant cua cong ty phat. */
        SINGLE_TENANT
    }

    private boolean enabled = false;

    /** Microsoft App ID cua Azure Bot resource. Cung la {@code aud} cua token den. */
    private String appId = "";
    /** Client secret cua app registration cua bot. */
    private String appPassword = "";

    private AppType appType = AppType.MULTI_TENANT;
    /** Bat buoc khi {@link AppType#SINGLE_TENANT}. */
    private String tenantId = "";

    /**
     * Dia chi tai lieu OpenID de lay khoa ky. De rong thi tu suy theo {@link #appType}.
     *
     * LUU Y duong dan cua Bot Framework KHONG theo chuan: la
     * {@code /v1/.well-known/openidconfiguration} chu khong phai
     * {@code /.well-known/openid-configuration}. Vi vay khong dung duoc
     * {@code NimbusJwtDecoder.withIssuerLocation}.
     */
    private String openidMetadataUrl = "";

    /** De rong thi tu suy theo {@link #appType}. */
    private List<String> allowedIssuers = new ArrayList<>();

    private int maxClockSkewSeconds = 300;

    // ------------------------------------------------ Pham vi tra loi

    /**
     * Pham vi khi KHONG xac dinh duoc nguoi dung (thieu {@code aadObjectId}, hoac chua
     * bat {@code rag.entra.enabled}).
     *
     * Rong = tu choi tra loi. Dat {@code *} de tro lai hanh vi cu cua Outgoing Webhook
     * (ai cung doc duoc moi thu) - chi nen dung khi toan bo tai lieu deu cong khai
     * trong noi bo.
     */
    private String unidentifiedDepartments = "";

    // ------------------------------------------------ Trai nghiem

    /** Loi chao khi nguoi dung mo cuoc tro chuyen hoac go /help. */
    private String greeting = """
            Xin chào! Tôi tra cứu giúp bạn tài liệu nội bộ.

            Cứ hỏi bằng tiếng Việt tự nhiên, ví dụ *"nghỉ phép năm được bao nhiêu ngày?"*.
            Tôi chỉ trả lời dựa trên tài liệu đã được nạp và luôn kèm nguồn để bạn kiểm chứng.
            """;

    /** So nguon toi da hien trong the tra loi. */
    private int maxCitations = 4;

    /** Teams cat tin nhan rat dai; cat chu dong de khong bi cat giua chung. */
    private int maxAnswerChars = 12_000;

    /** So luot xu ly song song. Moi luot ton 3-4 loi goi LLM nen khong de tha noi. */
    private int workerThreads = 8;

    /**
     * Gioi han so cau hoi moi nguoi moi phut.
     *
     * Dem theo NGUOI chu khong theo IP: moi tin nhan Teams deu den tu vai dia chi IP cua
     * Azure Bot Service, dem theo IP se gom ca cong ty vao mot o dem.
     */
    private int perUserPerMinute = 12;

    /**
     * Gioi han so cau hoi moi BOT moi phut - tran bao ve ca he thong.
     *
     * Han muc theo nguoi khong du: mot bot duoc cai cho ca cong ty thi 200 nguoi moi
     * nguoi hoi 1 cau van la 200 luot trong mot phut, va 3-4 loi goi LLM moi luot se
     * quay het han muc nha cung cap - lam chet ca nhung bot khac va ca giao dien web.
     * Cat o day de mot bot bi lam dung chi lam cham CHINH NO.
     *
     * Dat 0 de tat.
     */
    private int perBotPerMinute = 120;

    private int connectorTimeoutSeconds = 20;

    // ------------------------------------------------ Gia tri suy ra

    public String effectiveMetadataUrl() {
        if (!openidMetadataUrl.isBlank()) return openidMetadataUrl.strip();
        return appType == AppType.SINGLE_TENANT
                ? "https://login.microsoftonline.com/" + tenantId.strip()
                        + "/v2.0/.well-known/openid-configuration"
                : "https://login.botframework.com/v1/.well-known/openidconfiguration";
    }

    public List<String> effectiveIssuers() {
        if (!allowedIssuers.isEmpty()) return allowedIssuers;
        return appType == AppType.SINGLE_TENANT
                ? List.of("https://login.microsoftonline.com/" + tenantId.strip() + "/v2.0")
                : List.of("https://api.botframework.com");
    }

    /** Tenant dung de xin token GOI RA. Multi-tenant thi la tenant ao botframework.com. */
    public String outboundTokenTenant() {
        return appType == AppType.SINGLE_TENANT ? tenantId.strip() : "botframework.com";
    }

    public boolean isConfigured() {
        return enabled && !appId.isBlank() && !appPassword.isBlank()
                && (appType != AppType.SINGLE_TENANT || !tenantId.isBlank());
    }
}
