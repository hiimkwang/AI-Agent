package com.ai.aiagent.platform;

import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformModels.ChannelBinding;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformServiceTest {

    private static final String TEAM_HR = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String GROUP_HR = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String GROUP_ACC = "cccccccc-0000-0000-0000-000000000003";

    private PlatformRepository repository;
    private PlatformService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformRepository.class);
        when(repository.collections()).thenReturn(List.of(
                collection(1, "nhan-su", Set.of(GROUP_HR), false),
                collection(2, "ke-toan", Set.of(GROUP_ACC), false),
                collection(3, "noi-quy-chung", Set.of(GROUP_HR, GROUP_ACC), true),
                collection(4, "mat", Set.of(), false)));
        when(repository.bots()).thenReturn(List.of(
                bot(10, "chung", true, null),
                bot(11, "nhan-su", false, null),
                bot(12, "phap-che", false, "dddddddd-0000-0000-0000-000000000004")));
        when(repository.channelBindings()).thenReturn(List.of(
                new ChannelBinding(1L, 11L, "nhan-su", TEAM_HR, null),
                new ChannelBinding(2L, 12L, "phap-che", TEAM_HR, "19:kenh-phap-che")));
        service = new PlatformService(repository);
    }

    private static CollectionDef collection(long id, String slug, Set<String> acl, boolean channel) {
        return new CollectionDef(id, slug, slug, null, channel, "ACTIVE", acl, 5);
    }

    private static BotDef bot(long id, String slug, boolean isDefault, String teamsAppId) {
        return new BotDef(id, slug, slug, null, teamsAppId, isDefault, null, null, null, null,
                "ACTIVE", Set.of(), Set.of(), Set.of());
    }

    @Test
    @DisplayName("Doc duoc dung nhung tap co nhom Entra cua minh")
    void readableSlugsFollowGroups() {
        assertEquals(Set.of("nhan-su", "noi-quy-chung"), service.readableSlugs(Set.of(GROUP_HR)));
        assertEquals(Set.of("ke-toan", "noi-quy-chung"), service.readableSlugs(Set.of(GROUP_ACC)));
    }

    @Test
    @DisplayName("Tap chua gan nhom nao thi khong ai doc duoc")
    void collectionWithoutAclIsClosed() {
        assertFalse(service.readableSlugs(Set.of(GROUP_HR, GROUP_ACC)).contains("mat"));
    }

    @Test
    @DisplayName("Khong thuoc nhom nao thi khong doc duoc gi")
    void noGroupsMeansNothing() {
        assertTrue(service.readableSlugs(Set.of()).isEmpty());
    }

    @Test
    @DisplayName("Chi tap bat channel_allowed moi duoc tra loi cong khai")
    void channelAllowedIsExplicit() {
        assertEquals(Set.of("noi-quy-chung"), service.channelAllowedSlugs());
    }

    @Test
    @DisplayName("Khong khop luat nao => bot mac dinh")
    void fallsBackToDefaultBot() {
        assertEquals("chung", service.resolveBot(null, null, null).orElseThrow().slug());
        assertEquals("chung",
                service.resolveBot(null, "khong-co-trong-bang", null).orElseThrow().slug());
    }

    @Test
    @DisplayName("Rang buoc ca team ap dung cho moi channel cua team do")
    void teamBindingApplies() {
        assertEquals("nhan-su",
                service.resolveBot(null, TEAM_HR, "19:kenh-bat-ky").orElseThrow().slug());
    }

    @Test
    @DisplayName("Rang buoc den dung channel thang rang buoc ca team")
    void channelBindingBeatsTeamBinding() {
        assertEquals("phap-che",
                service.resolveBot(null, TEAM_HR, "19:kenh-phap-che").orElseThrow().slug());
    }

    @Test
    @DisplayName("teams_app_id thang moi rang buoc khac - moi bot mot Azure Bot rieng")
    void teamsAppIdWins() {
        Optional<BotDef> bot = service.resolveBot(
                "28:dddddddd-0000-0000-0000-000000000004", TEAM_HR, null);
        assertEquals("phap-che", bot.orElseThrow().slug());
    }

    @Test
    @DisplayName("So khop app id bo duoc tien to 28: cua Teams")
    void appIdPrefixIsStripped() {
        assertTrue(PlatformService.matchesAppId("ABC-123", "28:abc-123"));
        assertTrue(PlatformService.matchesAppId("abc-123", "abc-123"));
        assertFalse(PlatformService.matchesAppId("abc-123", "28:khac"));
    }

    @Test
    @DisplayName("Chua cau hinh ACL nao => bao de tang tren lui ve cau hinh P1")
    void detectsMissingAcl() {
        assertFalse(service.hasNoAcl());

        when(repository.collections()).thenReturn(List.of(collection(1, "a", Set.of(), false)));
        service.refresh();
        assertTrue(service.hasNoAcl());
    }

    @Test
    @DisplayName("Nhom da co thi khong tao lai - khong duoc de mat ACL dang co")
    void ensureCollectionIsANoOpWhenItAlreadyExists() {
        assertFalse(service.ensureCollection("nhan-su", "ingest"));
        verify(repository, never()).createCollection(any(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("Nhom chua khai thi tao, va tao o trang thai DONG (khong ACL)")
    void ensureCollectionDeclaresAClosedCollection() {
        assertTrue(service.ensureCollection("chatbot-giai-dieu", "ingest"));

        verify(repository).createCollection(eq("chatbot-giai-dieu"), eq("chatbot-giai-dieu"),
                any(), eq(false), eq("ingest"));
        // Khong co loi goi nao cap quyen doc: khai nhom chi cham dut trang thai mo coi,
        // no khong duoc tu mo du lieu cho bat ky ai.
        verify(repository, never()).setCollectionAcl(anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("Ma nhom rong thi bo qua, khong tao nhom rac")
    void ensureCollectionIgnoresBlankSlugs() {
        assertFalse(service.ensureCollection(null, "ingest"));
        assertFalse(service.ensureCollection("   ", "ingest"));
        verify(repository, never()).createCollection(any(), any(), any(), anyBoolean(), any());
    }
}
