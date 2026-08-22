package com.ai.aiagent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "rag.security")
@Getter
@Setter
public class SecurityProperties {

    private boolean enabled = true;
    private boolean allowAnonymous = false;
    private String headerName = "X-API-Key";
    private int maxQuestionLength = 4000;

    private List<Client> clients = new ArrayList<>();
    private final RateLimit rateLimit = new RateLimit();

    @Getter @Setter
    public static class Client {
        private String id;
        private String key = "";
        private String roles = "USER";
        private String departments = "*";

        public boolean isUsable() {
            return key != null && !key.isBlank();
        }
    }

    @Getter @Setter
    public static class RateLimit {
        private boolean enabled = true;
        /** Chi ap cho endpoint SINH cau tra loi - do la thu ton tien. */
        private int chatPerMinute = 30;
        private int adminPerMinute = 120;
        private int webhookPerMinute = 60;
        /**
         * Cac lenh doc nhe ma giao dien can de ve trang (/me, /settings, /models,
         * /categories, /conversations). Mot lan tai trang la ~5 request, nen neu dem
         * chung voi chat thi chi F5 vai lan la bi 429 du chua hoi cau nao.
         */
        private int otherPerMinute = 300;
    }
}
