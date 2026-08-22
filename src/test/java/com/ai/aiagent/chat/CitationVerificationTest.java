package com.ai.aiagent.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CitationVerificationTest {

    private PromptBuilder.CitationCheck check(String answer, int sources) {
        return PromptBuilder.verifyCitations(answer, sources);
    }

    @Test
    @DisplayName("Giu nguyen trich dan hop le")
    void keepsValidCitations() {
        PromptBuilder.CitationCheck r = check("Nghỉ 12 ngày [1]. Cộng thêm theo thâm niên [2].", 2);

        assertFalse(r.hadInvalid());
        assertEquals("Nghỉ 12 ngày [1]. Cộng thêm theo thâm niên [2].", r.answer());
    }

    @Test
    @DisplayName("Bo moc tro toi nguon khong ton tai va bao lai so do")
    void dropsInvalidCitations() {
        PromptBuilder.CitationCheck r = check("Nghỉ 12 ngày [1]. Theo quy định mới [5].", 2);

        assertTrue(r.hadInvalid());
        assertEquals(List.of(5), r.invalid());
        assertEquals("Nghỉ 12 ngày [1]. Theo quy định mới.", r.answer());
    }

    @Test
    @DisplayName("Chi bo moc, khong bo cau chua moc do")
    void keepsSentenceWhenCitationIsDropped() {
        PromptBuilder.CitationCheck r = check("Mức phụ cấp là 500.000 đồng [9].", 1);

        assertTrue(r.answer().contains("500.000 đồng"));
        assertFalse(r.answer().contains("[9]"));
    }

    @Test
    @DisplayName("Moc gop nhieu nguon: giu phan hop le, bo phan sai")
    void filtersInsideGroupedCitation() {
        PromptBuilder.CitationCheck r = check("Theo quy định [1, 4, 2].", 2);

        assertEquals(List.of(4), r.invalid());
        assertEquals("Theo quy định [1, 2].", r.answer());
    }

    @Test
    @DisplayName("Khong co nguon nao thi moi moc deu sai")
    void noSourcesMeansAllInvalid() {
        PromptBuilder.CitationCheck r = check("Không tìm thấy thông tin [1].", 0);

        assertEquals(List.of(1), r.invalid());
        assertEquals("Không tìm thấy thông tin.", r.answer());
    }

    @Test
    @DisplayName("Khong dung cham vao so khong phai trich dan")
    void leavesOtherBracketsAlone() {
        String answer = "Xem bảng [Phụ lục A] và mục 3.";
        assertEquals(answer, check(answer, 2).answer());
    }

    @Test
    @DisplayName("Cau tra loi rong khong lam vo buoc kiem tra")
    void toleratesEmptyAnswer() {
        assertFalse(check(null, 2).hadInvalid());
        assertFalse(check("", 2).hadInvalid());
    }
}
