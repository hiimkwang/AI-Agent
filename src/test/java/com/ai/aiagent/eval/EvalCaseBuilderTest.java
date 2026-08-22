package com.ai.aiagent.eval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EvalCaseBuilderTest {

    @Test
    @DisplayName("Cung mot cau hoi khac dau/hoa thuong/dau cau duoc coi la trung")
    void normalizesForDeduplication() {
        String a = EvalCaseBuilder.normalize("Nghỉ phép năm được bao nhiêu ngày?");
        assertEquals(a, EvalCaseBuilder.normalize("nghi phep nam duoc bao nhieu ngay"));
        assertEquals(a, EvalCaseBuilder.normalize("  NGHỈ PHÉP NĂM ĐƯỢC BAO NHIÊU NGÀY!!  "));
    }

    @Test
    @DisplayName("Hai cau hoi khac nhau khong bi gop nham")
    void keepsDifferentQuestionsApart() {
        assertNotEquals(
                EvalCaseBuilder.normalize("Nghỉ phép năm bao nhiêu ngày?"),
                EvalCaseBuilder.normalize("Nghỉ không lương bao nhiêu ngày?"));
    }

    @Test
    @DisplayName("Chuoi rong / null khong lam vo buoc chuan hoa")
    void toleratesEmpty() {
        assertEquals("", EvalCaseBuilder.normalize(null));
        assertEquals("", EvalCaseBuilder.normalize("   "));
        assertEquals("", EvalCaseBuilder.normalize("???"));
    }
}
