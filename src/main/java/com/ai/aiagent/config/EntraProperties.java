package com.ai.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "rag.entra")
@Getter
@Setter
public class EntraProperties {

    private boolean enabled = false;

    private String registrationId = "entra";

    private String tenantId = "";

    private String clientId = "";
    private String clientSecret = "";

    private List<String> allowedEmailDomains = new ArrayList<>(List.of("bsc.com.vn"));

    private Map<String, String> roleMappings = new LinkedHashMap<>();

    private String defaultRoles = "USER";

    private List<String> adminGroups = new ArrayList<>();

    private List<String> bootstrapAdminUpns = new ArrayList<>();

    private Map<String, String> groupDepartments = new LinkedHashMap<>();

    private String fallbackDepartments = "";

    private boolean graphEnabled = true;

    private int groupCacheMinutes = 15;

    private int graphTimeoutSeconds = 10;

    private String loginPath = "/oauth2/authorization/";

    public String authorizationUri() {
        return loginPath + registrationId;
    }

    public boolean hasGraphCredentials() {
        return !tenantId.isBlank() && !clientId.isBlank() && !clientSecret.isBlank();
    }
}
