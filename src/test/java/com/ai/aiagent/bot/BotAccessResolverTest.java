package com.ai.aiagent.bot;

import com.ai.aiagent.config.BotProperties;
import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import com.ai.aiagent.platform.PlatformService;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.security.EntraScopeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Lop nay quyet dinh tin nhan Teams doc duoc tai lieu nao. Rui ro lon nhat khong phai
 * "tu choi nham" ma la "tra loi trong channel bang quyen ca nhan cua nguoi hoi" - cau
 * tra loi hien cho ca channel trong khi bot van lam dung ACL cua nguoi hoi.
 */
class BotAccessResolverTest {

    private static final String OID = "11111111-1111-1111-1111-111111111111";
    private static final String TEAM = "22222222-2222-2222-2222-222222222222";
    private static final String GROUP_HR = "33333333-3333-3333-3333-333333333333";

    private BotProperties props;
    private PlatformService platform;
    private EntraScopeService entra;
    private BotAccessResolver resolver;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        props = new BotProperties();
        platform = mock(PlatformService.class);
        entra = mock(EntraScopeService.class);
        ObjectProvider<EntraScopeService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(entra);

        // Mac dinh: mot bot doc duoc ca hai tap, khong gioi han doi tuong.
        botServes("nhan-su", "noi-quy-chung");
        // Mac dinh: khong tap nao duoc tra loi trong channel.
        when(platform.channelAllowedSlugs()).thenReturn(Set.of());
        when(platform.snapshot()).thenReturn(new PlatformService.Snapshot(
                List.of(collection("nhan-su"), collection("noi-quy-chung")),
                List.of(), List.of()));

        resolver = new BotAccessResolver(props, platform, provider);
    }

    private static CollectionDef collection(String slug) {
        return new CollectionDef(1L, slug, slug, null, false, "ACTIVE", Set.of(GROUP_HR), 3);
    }

    private void botServes(String... slugs) {
        when(platform.resolveBot(any(), any(), any())).thenReturn(Optional.of(bot(Set.of(slugs),
                Set.of())));
    }

    private void botAudience(Set<String> groups) {
        when(platform.resolveBot(any(), any(), any()))
                .thenReturn(Optional.of(bot(Set.of("nhan-su", "noi-quy-chung"), groups)));
    }

    private static BotDef bot(Set<String> collections, Set<String> audienceGroups) {
        return new BotDef(1L, "chung", "Trợ lý", null, null, true, "Giọng thân thiện",
                "Xin chào", null, null, "ACTIVE", collections, audienceGroups, Set.of());
    }

    private void userReads(String... slugs) {
        when(entra.scopeOf(anyString(), any(), any())).thenReturn(
                new AccessScope(OID, "a@bsc.com.vn", Set.of("USER"), Set.of(slugs),
                        false, Set.of(GROUP_HR)));
    }

    private void userIsAdmin() {
        when(entra.scopeOf(anyString(), any(), any())).thenReturn(
                new AccessScope(OID, "a@bsc.com.vn", Set.of("ADMIN", "USER"), Set.of(),
                        true, Set.of(GROUP_HR)));
    }

    private static BotActivity personal() {
        return activity("personal", null, OID);
    }

    private static BotActivity channel() {
        return activity("channel", TEAM, OID);
    }

    private static BotActivity activity(String scope, String teamId, String aadObjectId) {
        return new BotActivity("message", "act-1", "nghỉ phép bao nhiêu ngày", "conv-1",
                scope, "29:from", "Nguyen Van A", aadObjectId, "tenant", teamId,
                "chan-1", "https://smba.trafficmanager.net/", "28:bot", "vi-VN", List.of());
    }

    // ============================================================ Chat rieng

    @Test
    @DisplayName("Chat rieng: pham vi = giao cua tap cua bot va quyen cua nguoi hoi")
    void personalIntersectsBotAndUser() {
        botServes("nhan-su");
        userReads("nhan-su", "ke-toan");

        BotAccessResolver.Resolution r = resolver.resolve(personal());

        assertTrue(r.allowed());
        // ke-toan bi loai vi bot khong phuc vu tap do
        assertEquals(Set.of("nhan-su"), r.scope().departments());
        assertEquals("chung", r.bot().slug());
    }

    @Test
    @DisplayName("Chat rieng: khong giao nhau => tu choi, va noi ro la do quyen")
    void personalWithNoOverlapIsDenied() {
        botServes("nhan-su");
        userReads("ke-toan");

        BotAccessResolver.Resolution r = resolver.resolve(personal());

        assertFalse(r.allowed());
        assertTrue(r.denial().contains("quyền"), r.denial());
    }

    @Test
    @DisplayName("Chua cau hinh bot nao => tu choi thay vi nem loi")
    void noBotConfiguredIsDenied() {
        when(platform.resolveBot(any(), any(), any())).thenReturn(Optional.empty());
        userReads("nhan-su");

        BotAccessResolver.Resolution r = resolver.resolve(personal());
        assertFalse(r.allowed());
        assertNotNull(r.denial());
    }

    // ============================================================ Doi tuong su dung

    @Test
    @DisplayName("Ngoai doi tuong su dung => tu choi ngay, khong tra cuu gi ca")
    void outsideAudienceIsDenied() {
        botAudience(Set.of("99999999-9999-9999-9999-999999999999"));
        userReads("nhan-su");

        BotAccessResolver.Resolution r = resolver.resolve(personal());
        assertFalse(r.allowed());
        assertTrue(r.denial().contains("nhóm được sử dụng"), r.denial());
    }

    /**
     * Doi tuong RONG = MO, khac han ACL collection (rong = dong). Cam dung bot khong bao
     * ve du lieu - du lieu duoc bao ve boi ACL collection.
     */
    @Test
    @DisplayName("Doi tuong su dung de rong nghia la mo cho moi nguoi da xac thuc")
    void emptyAudienceMeansOpen() {
        botAudience(Set.of());
        userReads("nhan-su");

        assertTrue(resolver.resolve(personal()).allowed());
    }

    // ============================================================ Channel

    @Test
    @DisplayName("Channel: chua tap nao bat channel_allowed => tu choi va huong dan nhan rieng")
    void channelWithoutPublicCollectionsIsDenied() {
        userReads("nhan-su");

        BotAccessResolver.Resolution r = resolver.resolve(channel());

        assertFalse(r.allowed());
        assertTrue(r.denial().contains("nhắn riêng"), r.denial());
    }

    @Test
    @DisplayName("Channel: giao them voi tap duoc phep tra loi cong khai")
    void channelIntersectsPublicCollections() {
        when(platform.channelAllowedSlugs()).thenReturn(Set.of("noi-quy-chung"));
        userReads("nhan-su", "noi-quy-chung");

        BotAccessResolver.Resolution r = resolver.resolve(channel());

        assertTrue(r.allowed());
        assertEquals(Set.of("noi-quy-chung"), r.scope().departments(),
                "nhan-su khong duoc tra loi cong khai du nguoi hoi co quyen doc");
    }

    /**
     * Chi thu hep danh sach tap la CHUA DU. {@code HybridRetriever} loc
     * {@code allowed_roles} bang {@code isAdmin() ? Set.of() : roles()}, nen ADMIN bo qua
     * ACL muc tai lieu va se keo tai lieu han che ra cho ca kenh.
     */
    @Test
    @DisplayName("Channel: ADMIN bi ha xuong USER")
    void channelStripsAdminRole() {
        when(platform.channelAllowedSlugs()).thenReturn(Set.of("noi-quy-chung"));
        userIsAdmin();

        BotAccessResolver.Resolution r = resolver.resolve(channel());

        assertTrue(r.allowed());
        assertFalse(r.scope().isAdmin(), "ADMIN phai bi ha xuong trong channel");
        assertFalse(r.scope().allDepartments());
        assertEquals(Set.of("noi-quy-chung"), r.scope().departments());
    }

    @Test
    @DisplayName("Chat rieng: ADMIN giu nguyen quyen - chi minh ho doc cau tra loi")
    void personalKeepsAdmin() {
        userIsAdmin();
        BotAccessResolver.Resolution r = resolver.resolve(personal());

        assertTrue(r.allowed());
        assertTrue(r.scope().isAdmin());
    }

    // ============================================================ Chua dinh danh

    @Test
    @DisplayName("Khong co aadObjectId va chua khai unidentified-departments => tu choi")
    void unidentifiedUserIsDeniedByDefault() {
        BotAccessResolver.Resolution r = resolver.resolve(activity("personal", null, null));

        assertFalse(r.allowed());
        assertNotNull(r.denial());
    }

    @Test
    @DisplayName("unidentified-departments=* tra lai hanh vi cu cua Outgoing Webhook")
    void unidentifiedWildcardRestoresOldBehaviour() {
        props.setUnidentifiedDepartments("*");

        BotAccessResolver.Resolution r = resolver.resolve(activity("personal", null, null));

        assertTrue(r.allowed());
        assertFalse(r.scope().isAdmin(), "khong duoc keo theo quyen ADMIN");
        assertEquals(Set.of("nhan-su", "noi-quy-chung"), r.scope().departments());
    }

    @Test
    @DisplayName("Chua dinh danh nhung hoi trong channel van bi rang buoc pham vi cong khai")
    void unidentifiedInChannelStillNarrowed() {
        props.setUnidentifiedDepartments("*");
        when(platform.channelAllowedSlugs()).thenReturn(Set.of("noi-quy-chung"));

        BotAccessResolver.Resolution r = resolver.resolve(activity("channel", TEAM, null));

        assertTrue(r.allowed());
        assertEquals(Set.of("noi-quy-chung"), r.scope().departments());
    }

    // ============================================================ Loi chao

    @Test
    @DisplayName("Loi chao lay theo bot phuc vu cuoc tro chuyen, khong phai chuoi chung")
    void greetingComesFromBot() {
        assertEquals(Optional.of("Xin chào"), resolver.greetingFor(personal()));
    }
}
