package com.ai.aiagent.chat;

import com.ai.aiagent.config.RagProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallTalkDetectorTest {

    private final RagProperties props = new RagProperties();
    private final SmallTalkDetector detector = new SmallTalkDetector(props);

    @Test
    @DisplayName("Loi chao tieng Viet va tieng Anh deu duoc nhan ra")
    void greetingsAreDetected() {
        for (String greeting : new String[]{
                "hello", "Hello!", "hi", "Hiii", "hey", "alo", "Good morning",
                "chao", "Chào", "chào bạn", "Xin chào", "CHÀO BOT", "chao ca nha",
                "cảm ơn", "Cam on ban", "thanks", "Thank you", "tạm biệt", "bye",
                "bạn là ai", "giúp", "Hướng dẫn"}) {
            assertTrue(detector.matches(greeting), "phai nhan ra la cau xa giao: " + greeting);
        }
    }

    @Test
    @DisplayName("Cau hoi that khong bi nham la loi chao, ke ca khi mo dau bang loi chao")
    void realQuestionsAreNotSmallTalk() {
        for (String question : new String[]{
                "Chào bạn, quy trình cấp phát thiết bị mất bao lâu?",
                "Hello, cho tôi hỏi về chính sách nghỉ phép",
                "Quy trình cấp phát thiết bị",
                "Giúp tôi tìm quy định về thanh toán công tác phí",
                "Mã số quy trình là gì?"}) {
            assertFalse(detector.matches(question), "khong duoc coi la xa giao: " + question);
        }
    }

    @Test
    @DisplayName("Tat cong tac thi moi cau deu di qua truy xuat nhu cu")
    void disabledMeansNoShortCircuit() {
        props.getChat().setSmallTalkEnabled(false);
        assertFalse(detector.matches("hello"));
    }

    @Test
    @DisplayName("Mau va loi chao cau hinh duoc, doi la co hieu luc ngay")
    void configuredPatternAndReplyWin() {
        props.getChat().setSmallTalkPattern("(?i)^ping$");
        props.getChat().setSmallTalkReply("pong");

        assertTrue(detector.matches("ping"));
        assertFalse(detector.matches("hello"));
        assertEquals("pong", detector.reply());

        // Recompiles instead of serving the previously cached pattern.
        props.getChat().setSmallTalkPattern("(?i)^pong$");
        assertFalse(detector.matches("ping"));
        assertTrue(detector.matches("pong"));
    }

    @Test
    @DisplayName("De trong la quay ve mac dinh, khong phai tat chuc nang")
    void blankFallsBackToDefault() {
        props.getChat().setSmallTalkPattern("");
        props.getChat().setSmallTalkReply("");

        assertTrue(detector.matches("xin chào"));
        assertEquals(SmallTalkDetector.DEFAULT_REPLY, detector.reply());
    }

    @Test
    @DisplayName("Mau hong khong duoc lam chet chat - quay ve mac dinh")
    void brokenPatternFallsBackInsteadOfThrowing() {
        props.getChat().setSmallTalkPattern("([unclosed");
        assertTrue(detector.matches("hello"));
    }
}
