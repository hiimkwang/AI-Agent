package com.ai.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "rag.bot")
@Getter
@Setter
public class BotProperties {

    public enum AppType {
        MULTI_TENANT,
        SINGLE_TENANT
    }

    private boolean enabled = false;

    private String appId = "";
    private String appPassword = "";

    private AppType appType = AppType.MULTI_TENANT;
    private String tenantId = "";

    private String openidMetadataUrl = "";

    private List<String> allowedIssuers = new ArrayList<>();

    private int maxClockSkewSeconds = 300;

    private String unidentifiedDepartments = "";

    private String greeting = """
            Xin chào! Tôi tra cứu giúp bạn tài liệu nội bộ.

            Cứ hỏi bằng tiếng Việt tự nhiên, ví dụ *"nghỉ phép năm được bao nhiêu ngày?"*.
            Tôi chỉ trả lời dựa trên tài liệu đã được nạp và luôn kèm nguồn để bạn kiểm chứng.
            """;

    private int maxCitations = 4;

    private int maxAnswerChars = 12_000;

    private int workerThreads = 8;

    private int perUserPerMinute = 12;

    private int perBotPerMinute = 120;

    private int connectorTimeoutSeconds = 20;

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

    public String outboundTokenTenant() {
        return appType == AppType.SINGLE_TENANT ? tenantId.strip() : "botframework.com";
    }

    public boolean isConfigured() {
        return enabled && !appId.isBlank() && !appPassword.isBlank()
                && (appType != AppType.SINGLE_TENANT || !tenantId.isBlank());
    }
}
