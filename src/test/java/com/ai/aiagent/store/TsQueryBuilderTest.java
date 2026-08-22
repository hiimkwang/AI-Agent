package com.ai.aiagent.store;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TsQueryBuilderTest {

    @Test
    @DisplayName("Nap lop khong nem loi du danh sach stopword co tu trung (loi ExceptionInInitializerError cu)")
    void staticInitDoesNotThrowOnDuplicateStopwords() {
        assertFalse(TsQueryBuilder.terms("nghi phep").isEmpty());
    }

    @Test
    @DisplayName("Bo dau tieng Viet, ke ca chu d gach ngang")
    void stripsDiacritics() {
        assertEquals("nghi phep hang nam", TsQueryBuilder.stripDiacritics("nghỉ phép hằng năm"));
        assertEquals("dieu Dong", TsQueryBuilder.stripDiacritics("điều Đồng"));
    }

    @Test
    @DisplayName("Ghep cac tu bang OR chu khong phai AND")
    void buildsOrQuery() {
        String query = TsQueryBuilder.orQuery("nghỉ phép hằng năm");
        assertTrue(query.contains("|"), "phai dung toan tu OR: " + query);
        assertFalse(query.contains("&"), "khong duoc dung AND: " + query);
        assertTrue(query.contains("'nghi'"), "phai bo dau truoc khi tao tsquery: " + query);
    }

    @Test
    @DisplayName("Cau hoi co dau va khong dau sinh ra CUNG mot tsquery")
    void accentedAndUnaccentedProduceSameQuery() {
        assertEquals(
                TsQueryBuilder.orQuery("Nghỉ phép hằng năm được bao nhiêu ngày?"),
                TsQueryBuilder.orQuery("nghi phep hang nam duoc bao nhieu ngay"));
    }

    @Test
    @DisplayName("Bo tu dung, giu tu co nghia")
    void dropsStopwords() {
        List<String> terms = TsQueryBuilder.terms("phụ cấp của các nhân viên là bao nhiêu");
        assertTrue(terms.contains("phu"));
        assertTrue(terms.contains("cap"));
        assertTrue(terms.contains("nhan"));
        assertFalse(terms.contains("cua"), "'cua' la stopword");
        assertFalse(terms.contains("la"), "'la' la stopword");
        assertFalse(terms.contains("bao"), "'bao' la stopword");
    }

    @Test
    @DisplayName("Khong the chen toan tu tsquery qua cau hoi")
    void sanitisesOperators() {
        String query = TsQueryBuilder.orQuery("nghi & phep | (xoa) !không <-> 'quote'");
        assertFalse(query.contains("&"));
        assertFalse(query.contains("!"));
        assertFalse(query.contains("("));
        assertFalse(query.contains("<->"));
        assertEquals(0, query.replace("'", "").chars().filter(c -> c == '\'').count());
    }

    @Test
    @DisplayName("Cau hoi khong con tu nao co nghia thi tra null de caller bo qua nhanh full-text")
    void returnsNullWhenNothingUseful() {
        assertNull(TsQueryBuilder.orQuery("là của và ???"));
        assertNull(TsQueryBuilder.orQuery("   "));
        assertNull(TsQueryBuilder.orQuery(null));
    }
}
