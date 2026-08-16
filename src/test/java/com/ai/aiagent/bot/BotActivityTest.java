package com.ai.aiagent.bot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotActivityTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private BotActivity parse(String json) throws Exception {
        return BotActivity.from(mapper.readTree(json));
    }

    /**
     * Teams chen mention vao dau tin nhan trong channel. Khong bo di thi ten bot lot vao
     * cau truy van va lam nhieu ca vector search lan full-text.
     */
    @Test
    @DisplayName("Bo mention <at>...</at> khoi noi dung cau hoi")
    void stripsMention() {
        assertEquals("nghỉ phép bao nhiêu ngày?",
                BotActivity.cleanText("<at>Trợ lý tài liệu</at> nghỉ phép bao nhiêu ngày?"));
        assertEquals("a b", BotActivity.cleanText("  a   <AT>Bot</AT>  b  "));
    }

    @Test
    @DisplayName("Doc duoc aadObjectId - thu ma Outgoing Webhook cu khong he co")
    void readsUserIdentity() throws Exception {
        BotActivity a = parse("""
                {"type":"message","text":"xin chao",
                 "from":{"id":"29:abc","name":"Nguyen Van A",
                         "aadObjectId":"AAAA1111-2222-3333-4444-555566667777"},
                 "conversation":{"id":"conv-1","conversationType":"personal"},
                 "serviceUrl":"https://smba.trafficmanager.net/"}""");

        assertEquals("aaaa1111-2222-3333-4444-555566667777", a.aadObjectId());
        assertTrue(a.isPersonal());
        assertTrue(a.isMessage());
    }

    @Test
    @DisplayName("Channel: lay duoc aadGroupId cua Team de anh xa pham vi tra loi")
    void readsTeamIdentity() throws Exception {
        BotActivity a = parse("""
                {"type":"message","text":"<at>Bot</at> quy dinh nghi phep",
                 "from":{"id":"29:abc"},
                 "conversation":{"id":"conv-2","conversationType":"channel"},
                 "channelData":{"team":{"aadGroupId":"BBBB1111-2222-3333-4444-555566667777"},
                                "channel":{"id":"19:chan"},
                                "tenant":{"id":"CCCC0000-0000-0000-0000-000000000000"}},
                 "serviceUrl":"https://smba.trafficmanager.net/"}""");

        assertTrue(a.isChannel());
        assertEquals("bbbb1111-2222-3333-4444-555566667777", a.teamAadGroupId());
        assertEquals("cccc0000-0000-0000-0000-000000000000", a.tenantId());
        assertEquals("quy dinh nghi phep", a.text());
    }

    @Test
    @DisplayName("Thieu conversationType thi suy tu viec co team hay khong")
    void infersScopeWhenMissing() throws Exception {
        assertTrue(parse("""
                {"type":"message","conversation":{"id":"c"}}""").isPersonal());

        assertTrue(parse("""
                {"type":"message","conversation":{"id":"c"},
                 "channelData":{"team":{"aadGroupId":"x"}}}""").isChannel());
    }

    /**
     * Trong mot Team, moi lan co nguoi moi vao channel deu sinh conversationUpdate.
     * Chao moi su kien do la bot spam ca kenh.
     */
    @Test
    @DisplayName("Chi coi la 'bot vua duoc cai' khi CHINH bot nam trong membersAdded")
    void detectsOnlyBotInstall() throws Exception {
        String template = """
                {"type":"conversationUpdate","recipient":{"id":"28:bot"},
                 "conversation":{"id":"c"},"membersAdded":[{"id":"%s"}]}""";

        assertTrue(parse(template.formatted("28:bot")).isBotAdded());
        assertFalse(parse(template.formatted("29:nguoi-moi")).isBotAdded());
    }

    @Test
    @DisplayName("Payload rong khong lam vo bo phan tich")
    void toleratesEmptyPayload() throws Exception {
        BotActivity a = parse("{}");
        assertNull(a.type());
        assertEquals("", a.text());
        assertFalse(a.isMessage());
        assertTrue(a.membersAdded().isEmpty());
    }
}
