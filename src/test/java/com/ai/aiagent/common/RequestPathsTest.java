package com.ai.aiagent.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class RequestPathsTest {

    private static MockHttpServletRequest request(String context, String uri) {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", uri);
        r.setContextPath(context);
        return r;
    }

    @Test
    @DisplayName("Chay o goc: duong dan khong doi")
    void rootDeploymentIsUnchanged() {
        assertThat(RequestPaths.within(request("", "/api/v1/rag/admin/documents")))
                .isEqualTo("/api/v1/rag/admin/documents");
    }

    @Test
    @DisplayName("Duoi tien to: context path bi cat bo")
    void contextPathIsStripped() {
        assertThat(RequestPaths.within(request("/rag", "/rag/api/v1/rag/admin/documents")))
                .isEqualTo("/api/v1/rag/admin/documents");
    }

    @Test
    @DisplayName("Goc cua ung dung duoi tien to tra ve '/'")
    void contextRootBecomesSlash() {
        assertThat(RequestPaths.within(request("/rag", "/rag"))).isEqualTo("/");
    }

    @Test
    @DisplayName("Tien to trung ten voi doan dau duong dan van cat dung mot lan")
    void onlyTheContextPrefixIsRemoved() {
        assertThat(RequestPaths.within(request("/rag", "/rag/rag/api")))
                .isEqualTo("/rag/api");
    }

    @Test
    @DisplayName("Cac tien to loc cua filter phai khop CA hai cach trien khai")
    void filterPrefixesMatchUnderBothDeployments() {
        // Regression: these four checks are what RateLimitFilter, AuditFilter,
        // EntraScopeFilter and the CSRF exemptions match on. Built on
        // getRequestURI() they all fail open once a context path exists.
        for (String context : new String[]{"", "/rag"}) {
            assertThat(RequestPaths.within(request(context, context + "/api/v1/rag/admin/bots")))
                    .startsWith("/api/v1/rag/admin");
            assertThat(RequestPaths.within(request(context, context + "/api/v1/rag/chat")))
                    .startsWith("/api/");
            assertThat(RequestPaths.within(request(context, context + "/api/messages")))
                    .isEqualTo("/api/messages");
            assertThat(RequestPaths.within(request(context, context + "/oauth2/authorization/entra")))
                    .startsWith("/oauth2/");
        }
    }
}
