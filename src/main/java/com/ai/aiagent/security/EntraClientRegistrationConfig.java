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

/**
 * Khai bao ClientRegistration cua Entra ID BANG CODE, khong qua
 * {@code spring.security.oauth2.client.registration.*}.
 *
 * Ly do: neu khai bao trong properties voi gia tri mac dinh rong
 * ({@code client-id=${ENTRA_CLIENT_ID:}}) thi Spring Boot van coi la "da cau hinh"
 * (chuoi rong khac null) va se NEM LOI LUC KHOI DONG khi chua ai bat SSO - tuc la
 * hong ca moi truong dev dang chay bang API key. Dat dieu kien
 * {@code rag.entra.enabled=true} o day thi bat SSO thuc su la mot cong tac.
 *
 * Cung khong dung {@code ClientRegistrations.fromIssuerLocation}: ham do goi mang ngay
 * luc khoi dong, nghia la Entra cham hay mang truc trac la ung dung khong len duoc.
 * Cac endpoint cua Entra on dinh va co tai lieu, khai bao thang an toan hon.
 *
 * KHONG khai bao {@code userInfoUri}: moi thu can cho phan quyen ({@code oid},
 * {@code tid}, {@code preferred_username}, {@code roles}) deu co san trong ID token.
 * Goi them userinfo cua Graph chi them mot diem hong va mot quyen phai xin.
 */
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

        log.info("Entra ID: da dang ky client '{}' cho tenant {}.",
                props.getRegistrationId(), tenant);
        return new InMemoryClientRegistrationRepository(registration);
    }
}
