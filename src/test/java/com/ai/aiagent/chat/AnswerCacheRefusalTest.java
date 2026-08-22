package com.ai.aiagent.chat;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.security.AccessScope;
import com.ai.aiagent.store.AnswerCacheRepository;
import com.ai.aiagent.store.StoreModels.Turn;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AnswerCacheRefusalTest {

    private AnswerCacheRepository repository;
    private RagProperties props;
    private AnswerCacheService service;

    @BeforeEach
    void setUp() {
        repository = mock(AnswerCacheRepository.class);
        props = new RagProperties();
        service = new AnswerCacheService(repository, props, new ObjectMapper());
    }

    private static AccessScope scope() {
        return AccessScope.internal();
    }

    @Test
    @DisplayName("Chinh cau tu choi trong anh chup man hinh phai bi coi la khong dang cache")
    void theRefusalFromTheScreenshotIsDetected() {
        assertTrue(service.looksLikeRefusal(
                "Tôi không tìm thấy thông tin về lệnh STO trong tài liệu nội bộ."));
    }

    @Test
    @DisplayName("Cau tu choi ma system prompt day cho mo hinh cung bi bat")
    void thePhraseTheSystemPromptTeachesIsDetected() {
        assertTrue(service.looksLikeRefusal(
                "Tôi không tìm thấy thông tin này trong tài liệu nội bộ."));
        assertTrue(service.looksLikeRefusal(
                "Tài liệu không đề cập đến nội dung bạn hỏi."));
        assertTrue(service.looksLikeRefusal(
                "Không đủ thông tin để trả lời câu hỏi này."));
    }

    @Test
    @DisplayName("Cau tra loi that van duoc cache")
    void arealAnswerIsStillCacheable() {
        assertFalse(service.looksLikeRefusal("""
                Lệnh STO là lệnh dừng, được đặt ở trạng thái "Chờ kích hoạt" và chỉ kích hoạt
                khi giá khớp gần nhất thỏa mãn điều kiện kích hoạt [1].
                """));
    }

    @Test
    @DisplayName("Cau tu choi khong duoc ghi vao cache")
    void refusalsAreNotStored() {
        service.store("Lệnh STO", scope(), null, "OPENAI", "gpt-4o-mini",
                "Tôi không tìm thấy thông tin về lệnh STO trong tài liệu nội bộ.",
                List.of(), null);

        verify(repository, never()).put(anyString(), anyString(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    @DisplayName("Mau regex hong thi van cache binh thuong, khong duoc lam chet duong tra loi")
    void anInvalidPatternFailsOpen() {
        props.getCache().setRefusalPattern("([unclosed");
        assertFalse(service.looksLikeRefusal("Bất cứ câu nào."));
    }

    @Test
    @DisplayName("De trong mau regex la tat han viec kiem tra")
    void aBlankPatternDisablesTheCheck() {
        props.getCache().setRefusalPattern("");
        assertFalse(service.looksLikeRefusal(
                "Tôi không tìm thấy thông tin về lệnh STO trong tài liệu nội bộ."));
    }

    private String scopeKeyFor(String context) {
        AnswerCacheRepository repo = mock(AnswerCacheRepository.class);
        AnswerCacheService svc = new AnswerCacheService(repo, props, new ObjectMapper());
        svc.store("Lệnh cơ sở ấy", scope(), null, "OPENAI", "gpt-4o-mini",
                "Một câu trả lời thật sự.", List.of(), null, context);
        var captor = forClass(String.class);
        verify(repo).put(anyString(), captor.capture(), anyString(), anyString(),
                any(), anyString(), anyString(), any(), anyInt());
        return captor.getValue();
    }

    @Test
    @DisplayName("Cau hoi dau tien khong co ngu canh thi dung chung mot o cache")
    void aFirstQuestionKeepsTheSharedEntry() {
        assertNull(AnswerCacheService.contextFingerprint(List.of()));
        assertNull(AnswerCacheService.contextFingerprint(null));
        assertFalse(scopeKeyFor(null).contains("ctx:"));
    }

    @Test
    @DisplayName("Cung mot cau hoi tiep o hai hoi thoai khac nhau KHONG duoc dung chung cache")
    void aFollowUpDoesNotLeakBetweenConversations() {
        String a = AnswerCacheService.contextFingerprint(List.of(
                new Turn("user", "Lệnh điều kiện OCO"),
                new Turn("assistant", "OCO là lệnh kết hợp...")));
        String b = AnswerCacheService.contextFingerprint(List.of(
                new Turn("user", "Quy trình nghỉ phép"),
                new Turn("assistant", "Nhân viên nghỉ phép cần...")));

        assertNotEquals(a, b);
        assertNotEquals(scopeKeyFor(a), scopeKeyFor(b));
    }

    @Test
    @DisplayName("Cung mot ngu canh thi van dung lai duoc cache")
    void thesameContextStillShares() {
        String ctx = AnswerCacheService.contextFingerprint(List.of(
                new Turn("user", "Lệnh điều kiện OCO")));
        assertEquals(scopeKeyFor(ctx), scopeKeyFor(ctx));
    }

    @Test
    @DisplayName("Bo phieu xau xoa dung cau hoi do khoi cache")
    void invalidateDeletesByQuestion() {
        service.invalidate("  Lệnh STO  ");
        verify(repository).deleteByQuestion("Lệnh STO");
    }

    @Test
    @DisplayName("Cau hoi rong thi khong goi xuong DB")
    void invalidateIgnoresBlankQuestions() {
        service.invalidate(null);
        service.invalidate("   ");
        verify(repository, never()).deleteByQuestion(anyString());
    }
}
