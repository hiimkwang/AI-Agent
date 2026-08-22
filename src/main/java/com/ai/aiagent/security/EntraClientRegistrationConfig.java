package com.ai.aiagent.security;

import com.ai.aiagent.config.EntraProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

@Configuration
@ConditionalOnProperty(prefix = "rag.entra", name = "enabled", havingValue = "true")
@Slf4j
public class EntraClientRegistrationConfig {

    private static final String BASE = "https://login.microsoftonline.com/";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(EntraProperties props) {
        String tenant = props.getTenantId() == null ? "" : props.getTenantId().strip();
        if (tenant.isBlank() || props.getClientId().isBlank()) {
            throw new IllegalStateException(
                    "rag.entra.enabled=true nhung thieu ENTRA_TENANT_ID hoac ENTRA_CLIENT_ID. "
                    + "Dat day du hai bien nay, hoac dat rag.entra.enabled=false de chay bang API key.");
        }

        // Built in code rather than declared under spring.security.oauth2.client
        // .registration.*: empty defaults there read as "configured" and break startup
        // when SSO is off. fromIssuerLocation is avoided as it calls out at startup.
        ClientRegistration registration = ClientRegistration
                .withRegistrationId(props.getRegistrationId())
                .clientName("Tài khoản công ty")
                .clientId(props.getClientId())
                .clientSecret(props.getClientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                .scope("openid", "profile", "email")
                .authorizationUri(BASE + tenant + "/oauth2/v2.0/authorize")
                .tokenUri(BASE + tenant + "/oauth2/v2.0/token")
                .jwkSetUri(BASE + tenant + "/discovery/v2.0/keys")
                .issuerUri(BASE + tenant + "/v2.0")
                .userNameAttributeName("preferred_username")
                .build();

        log.info("Entra ID client '{}' registered for tenant {}.",
                props.getRegistrationId(), tenant);
        return new InMemoryClientRegistrationRepository(registration);
    }
}
