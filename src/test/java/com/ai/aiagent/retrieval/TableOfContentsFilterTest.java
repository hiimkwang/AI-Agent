package com.ai.aiagent.retrieval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableOfContentsFilterTest {

    /** Doan that lay tu "Lenh dieu kien phai sinh STO TLO.docx" tren UAT. */
    private static final String REAL_TOC = """
            2.2.5\tGiải pháp thực hiện\t21
            2.2.6\tĐánh giá ảnh hưởng\t22
            2.3\tTrailing Buy (Lệnh Mua xu hướng)\t22
            2.3.1\tMô tả yêu cầu\t22
            2.3.2\tĐặc tả chức năng\t22
            2.3.3\tVí dụ\t25
            2.3.4\tGiao diện đặt lệnh\t29
            2.3.5\tGiải pháp thực hiện\t30
            """;

    private static final String REAL_SPEC = """
            ## Stop – Limit Up (Thị trường xu hướng tăng) > Đặc tả chức năng
            - Lệnh dừng được đặt trong tất cả các phiên, loại lệnh LO
            - Lệnh mới đặt ở trạng thái "Chờ kích hoạt"
            - Kích hoạt lệnh: lệnh dừng chỉ được kích hoạt khi có tín hiệu khớp thỏa mãn
              điều kiện kích hoạt (cuối phiên ATO, phiên liên tục)
            - Khi hết hiệu lực, lệnh chuyển sang trạng thái "Hết hiệu lực"
            """;

    @Test
    @DisplayName("Muc luc that cua tai lieu Word bi loai")
    void realTableOfContentsIsDetected() {
        assertTrue(TableOfContentsFilter.isTableOfContents(REAL_TOC));
    }

    @Test
    @DisplayName("Muc luc kieu dau cham noi bi loai")
    void dottedLeaderTableOfContentsIsDetected() {
        assertTrue(TableOfContentsFilter.isTableOfContents("""
                Điều 1. Phạm vi điều chỉnh ................. 3
                Điều 2. Đối tượng áp dụng .................. 4
                Điều 3. Giải thích từ ngữ .................. 5
                Điều 4. Nguyên tắc chung ................... 7
                Điều 5. Hiệu lực thi hành .................. 12
                """));
    }

    @Test
    @DisplayName("Dac ta that KHONG bi loai - day moi la doan tra loi duoc")
    void realSpecificationIsKept() {
        assertFalse(TableOfContentsFilter.isTableOfContents(REAL_SPEC));
    }

    @Test
    @DisplayName("Van xuoi ket thuc bang so khong bi nham la muc luc")
    void proseEndingInNumbersIsKept() {
        assertFalse(TableOfContentsFilter.isTableOfContents("""
                Biên độ dao động giá của cổ phiếu niêm yết trên HOSE là 7
                Biên độ dao động giá của cổ phiếu niêm yết trên HNX là 10
                Thời gian thanh toán là T+2
                Phí giao dịch tối đa theo quy định hiện hành là 0.5
                Số lượng đặt lệnh tối thiểu là 100
                Bước giá áp dụng cho cổ phiếu dưới 10.000 đồng là 10
                """));
    }

    @Test
    @DisplayName("Vai dong danh so le te chua du de coi la muc luc")
    void aFewNumberedLinesAreNotEnough() {
        assertFalse(TableOfContentsFilter.isTableOfContents("""
                ## Quy trình phê duyệt
                2.1 Tiếp nhận hồ sơ 3
                2.2 Thẩm định 4
                Sau khi thẩm định, hồ sơ được chuyển sang bộ phận phê duyệt trong vòng
                hai ngày làm việc kể từ ngày nhận đủ giấy tờ hợp lệ.
                """));
    }

    @Test
    @DisplayName("Doan dai khong khop phai xong tuc thi - ban regex cu backtrack 26 giay")
    void aLongNonMatchingChunkIsFast() {
        // Dong bat dau bang so nhieu cap nhung KHONG ket thuc bang so trang: day chinh la
        // truong hop lam ban regex cu quay lui theo binh phuong do dai dong.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("2.3.4 ").append("noi dung rat dai khong ket thuc bang so ".repeat(40))
                    .append("het").append(System.lineSeparator());
        }
        String text = sb.toString();

        long start = System.nanoTime();
        assertFalse(TableOfContentsFilter.isTableOfContents(text));
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertTrue(ms < 500, "quet 200 dong dai phai duoi 500ms, thuc te " + ms + "ms");
    }

    @Test
    @DisplayName("Rong hoac null thi khong loai")
    void blankIsNotATableOfContents() {
        assertFalse(TableOfContentsFilter.isTableOfContents(null));
        assertFalse(TableOfContentsFilter.isTableOfContents("   "));
    }
}
