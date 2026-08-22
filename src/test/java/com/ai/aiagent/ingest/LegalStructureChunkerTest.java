package com.ai.aiagent.ingest;

import com.ai.aiagent.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegalStructureChunkerTest {

    private RagProperties props;
    private MarkdownChunker chunker;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        props.getChunking().setParentMaxChars(600);
        props.getChunking().setChildMaxChars(300);
        chunker = new MarkdownChunker(props);
    }

    @Test
    @DisplayName("Nhan dien Phan / Chuong / Muc / Dieu / Phu luc, long dung thu tu")
    void detectsLegalMarkers() {
        assertEquals(2, MarkdownChunker.legalHeadingLevel("PHẦN THỨ NHẤT"));
        assertEquals(3, MarkdownChunker.legalHeadingLevel("Chương II"));
        assertEquals(3, MarkdownChunker.legalHeadingLevel("CHƯƠNG 2 - QUY ĐỊNH CHUNG"));
        assertEquals(3, MarkdownChunker.legalHeadingLevel("Phụ lục 01"));
        assertEquals(4, MarkdownChunker.legalHeadingLevel("Mục 1. Nguyên tắc"));
        assertEquals(5, MarkdownChunker.legalHeadingLevel("Điều 7. Nghỉ phép hằng năm"));
    }

    @Test
    @DisplayName("Nhan dien duoc ca khi tai lieu mat dau hoac in dam")
    void toleratesFormattingAndMissingDiacritics() {
        assertEquals(5, MarkdownChunker.legalHeadingLevel("**Điều 12. Chế độ ốm đau**"));
        assertEquals(5, MarkdownChunker.legalHeadingLevel("Dieu 3. Pham vi ap dung"));
        assertEquals(3, MarkdownChunker.legalHeadingLevel("Chuong I"));
    }

    @Test
    @DisplayName("Khong nham cau van co chua tu 'Dieu'/'Chuong' voi tieu de")
    void doesNotMatchProseContainingKeywords() {
        assertEquals(0, MarkdownChunker.legalHeadingLevel("Điều này quy định về chế độ nghỉ phép."));
        assertEquals(0, MarkdownChunker.legalHeadingLevel("Theo Điều 7 nêu trên, người lao động…"));
        assertEquals(0, MarkdownChunker.legalHeadingLevel("Chương trình đào tạo nội bộ"));
        assertEquals(0, MarkdownChunker.legalHeadingLevel(""));
    }

    @Test
    @DisplayName("Dong qua dai khong phai tieu de ma la cau co chua tu do")
    void rejectsOverlongLines() {
        assertEquals(0, MarkdownChunker.legalHeadingLevel("Điều 7. " + "x".repeat(300)));
    }

    private static final String QUY_CHE = """
            Chương I

            Điều 1. Phạm vi áp dụng

            Quy chế này áp dụng cho toàn thể cán bộ nhân viên của công ty, bao gồm cả
            nhân viên thử việc và nhân viên thời vụ có hợp đồng từ ba tháng trở lên.

            Điều 2. Nghỉ phép hằng năm

            Người lao động làm việc đủ 12 tháng được nghỉ 12 ngày phép hưởng nguyên lương.
            Cứ mỗi 5 năm làm việc thì số ngày nghỉ phép được cộng thêm 1 ngày.

            Chương II

            Điều 3. Nghỉ không lương

            Người lao động có thể xin nghỉ không lương tối đa 30 ngày trong một năm, phải
            được trưởng đơn vị chấp thuận bằng văn bản trước ít nhất 5 ngày làm việc.
            """;

    @Test
    @DisplayName("Duong dan heading tu dong thanh 'Chuong > Dieu' du tai lieu khong co heading Markdown")
    void buildsHeadingPathFromLegalStructure() {
        List<MarkdownChunker.Chunk> chunks = chunker.chunk(QUY_CHE);

        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(c ->
                        c.headingPath().contains("Chương I") && c.headingPath().contains("Điều 2")),
                "phai co chunk mang duong dan 'Chương I > Điều 2…', thuc te: "
                        + chunks.stream().map(MarkdownChunker.Chunk::headingPath).distinct().toList());
    }

    @Test
    @DisplayName("Parent khong bao gio chua noi dung cua hai Dieu khac nhau")
    void parentNeverSpansTwoArticles() {
        for (MarkdownChunker.Chunk chunk : chunker.chunk(QUY_CHE)) {
            long articles = chunk.parentContent().lines()
                    .filter(line -> MarkdownChunker.legalHeadingLevel(line) == 5)
                    .count();
            assertTrue(articles <= 1,
                    "parent chua nhieu hon mot Dieu:\n" + chunk.parentContent());
        }
    }

    @Test
    @DisplayName("Dieu ngan KHONG bi gop vao Dieu ke tiep va gan nham nhan")
    // Regression: a short Dieu merged into the next one took its heading, so answers
    // cited the wrong article. Must run with the default min-section-chars.
    void shortArticleIsNotMergedIntoTheNextOne() {
        List<MarkdownChunker.Chunk> chunks = chunker.chunk(QUY_CHE);

        MarkdownChunker.Chunk scopeChunk = chunks.stream()
                .filter(c -> c.content().contains("Phạm vi") || c.content().contains("áp dụng cho toàn thể"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("mat noi dung cua Dieu 1"));

        assertTrue(scopeChunk.headingPath().contains("Điều 1"),
                "noi dung Dieu 1 bi gan nham nhan: " + scopeChunk.headingPath());
    }

    @Test
    @DisplayName("Tat co che thi tro lai hanh vi cu - khong con nhan dien Dieu/Chuong")
    void canBeDisabled() {
        props.getChunking().setLegalStructureEnabled(false);
        List<MarkdownChunker.Chunk> chunks = chunker.chunk(QUY_CHE);

        assertTrue(chunks.stream().allMatch(c -> c.headingPath().isBlank()),
                "tat co che thi khong duoc sinh duong dan heading nao");
    }

    @Test
    @DisplayName("Dinh danh tai lieu duoc gan vao van ban dem di nhung")
    void embedTextCarriesDocumentIdentity() {
        String identity = MarkdownChunker.documentIdentity(
                "Quy chế nghỉ phép", "QĐ-123/2026/QĐ-BSC", LocalDate.of(2026, 1, 1));

        assertEquals("[Quy chế nghỉ phép — QĐ-123/2026/QĐ-BSC — hiệu lực từ 2026-01-01]", identity);

        MarkdownChunker.Chunk chunk = chunker.chunk(QUY_CHE).get(0);
        String embedded = chunk.embedText(identity, null);

        assertTrue(embedded.startsWith(identity));
        assertTrue(embedded.contains(chunk.content()));
    }

    @Test
    @DisplayName("Khong co metadata nao thi khong them dong nhieu vao moi chunk")
    void documentIdentityIsEmptyWhenNothingKnown() {
        assertEquals("", MarkdownChunker.documentIdentity(null, null, null));
        assertEquals("", MarkdownChunker.documentIdentity("  ", "", null));
    }
}
