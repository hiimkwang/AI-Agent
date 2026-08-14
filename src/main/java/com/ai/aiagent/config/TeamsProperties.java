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
    /** Tat hoan toan endpoint webhook neu false. */
    private boolean enabled = false;
    /** Secret cua Outgoing Webhook trong Teams (chuoi base64). Rong = tu choi moi request. */
    private String hmacSecret = "";
    /** Danh cho phuong thuc xac thuc bang JWT cua Bot Framework (chua dung o che do HMAC). */
    private int maxClockSkewSeconds = 300;
}
