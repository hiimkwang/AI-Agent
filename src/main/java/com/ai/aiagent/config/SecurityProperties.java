package com.ai.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Cau hinh bao mat: {@code rag.security.*}.
 *
 * Mo hinh don gian nhung du dung cho service noi bo: moi client la mot API key
 * kem danh sach role va danh sach phong ban duoc phep doc. Phong ban dung cho
 * ACL tai lieu - client CHI thay tai lieu thuoc phong ban cua minh, thay vi tin
 * vao tham so {@code category} do client tu khai nhu truoc day.
 */
@Component
@ConfigurationProperties(prefix = "rag.security")
@Getter
@Setter
public class SecurityProperties {

    private boolean enabled = true;
    /** Chi bat tren may dev: khong co API key nao van cho vao voi quyen ADMIN. */
    private boolean allowAnonymous = false;
    private String headerName = "X-API-Key";
    private int maxQuestionLength = 4000;

    private List<Client> clients = new ArrayList<>();
    private final RateLimit rateLimit = new RateLimit();

    @Getter @Setter
    public static class Client {
        private String id;
        private String key = "";
        /** Vi du: ADMIN,USER */
        private String roles = "USER";
        /** {@code *} = tat ca phong ban. Vi du: NHAN-SU,KE-TOAN */
        private String departments = "*";

        public boolean isUsable() {
            return key != null && !key.isBlank();
        }
    }

    @Getter @Setter
    public static class RateLimit {
        private boolean enabled = true;
        private int chatPerMinute = 30;
        private int adminPerMinute = 120;
        private int webhookPerMinute = 60;
    }
}
