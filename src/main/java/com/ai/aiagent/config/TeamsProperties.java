package com.ai.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "rag.teams")
@Getter
@Setter
public class TeamsProperties {
    private boolean enabled = false;
    private String hmacSecret = "";
    private int maxClockSkewSeconds = 300;
}
