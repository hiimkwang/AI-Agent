package com.ai.aiagent.ingest;

import com.ai.aiagent.config.RagProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownChunkerTest {

    private RagProperties props;
    private MarkdownChunker chunker;

    @BeforeEach
    void setUp() {
        props = new RagProperties();
        chunker = new MarkdownChunker(props);
    }

    @Test
    @DisplayName("Chunk mang duong dan heading day du theo cap")
    void buildsHeadingPath() {
        String markdown = """
                # Noi quy lao dong

                ## Chuong II - Che do nghi

                ### Dieu 3. Nghi phep hang nam

                Nhan vien lam viec du 12 thang duoc nghi phep 12 ngay co huong nguyen luong.
                """;
        List<MarkdownChunker.Chunk> chunks = chunker.chunk(markdown);

        assertFalse(chunks.isEmpty());
        assertEquals("Noi quy lao dong > Chuong II - Che do nghi > Dieu 3. Nghi phep hang nam",
                chunks.get(0).headingPath());
    }

    @Test
    @DisplayName("Ho tro heading kieu setext (=== / ---) ma flexmark tung sinh ra")
    void supportsSetextHeadings() {
        String markdown = """
                Quy trinh cong tac
                ==================

                Dinh muc chi phi
                ----------------

                Tien phong khach san toi da 800.000 dong mot dem cho cap nhan vien.
                """;
        List<MarkdownChunker.Chunk> chunks = chunker.chunk(markdown);

        assertFalse(chunks.isEmpty());
        assertEquals("Quy trinh cong tac > Dinh muc chi phi", chunks.get(0).headingPath());
    }

    @Test
    @DisplayName("Bang Markdown khong bi cat doi giua cac hang")
    void keepsSmallTableIntact() {
        String markdown = """
                # Phu cap

                ## Muc phu cap

                | Truong hop | Ty le |
                | --- | --- |
                | Ngay thuong | 150% |
                | Ngay nghi hang tuan | 200% |
                | Ngay le | 300% |
                """;
        List<MarkdownChunker.Chunk> chunks = chunker.chunk(markdown);

        boolean intact = chunks.stream().anyMatch(c ->
                c.content().contains("150%") && c.content().contains("200%")
                        && c.content().contains("300%"));
        assertTrue(intact, "bang nho phai nam tron trong mot chunk:\n"
                + chunks.stream().map(MarkdownChunker.Chunk::content).toList());
    }

    @Test
    @DisplayName("Bang qua lon bi cat theo hang NHUNG lap lai dong header o moi manh")
    void repeatsHeaderWhenSplittingLargeTable() {
        StringBuilder markdown = new StringBuilder("# Bang gia\n\n## Chi tiet\n\n");
        markdown.append("| Ma san pham | Ten san pham | Don gia |\n| --- | --- | --- |\n");
        for (int i = 0; i < 120; i++) {
            markdown.append("| SP").append(i)
                    .append(" | San pham co ten rat dai de bang vuot qua gioi han parent ")
                    .append(i).append(" | ").append(i * 1000).append(" |\n");
        }
        props.getChunking().setParentMaxChars(900);
        props.getChunking().setChildMaxChars(400);

        List<MarkdownChunker.Chunk> chunks = chunker.chunk(markdown.toString());

        long parentsWithHeader = chunks.stream()
                .map(MarkdownChunker.Chunk::parentContent)
                .distinct()
                .filter(p -> p.contains("Ma san pham"))
                .count();
        long parentsTotal = chunks.stream()
                .map(MarkdownChunker.Chunk::parentContent)
                .distinct()
                .count();

        assertTrue(parentsTotal > 1, "bang lon phai bi chia thanh nhieu parent");
        assertEquals(parentsTotal, parentsWithHeader,
                "MOI manh cua bang phai co dong header de doc duoc doc lap");
    }

    @Test
    @DisplayName("Code fence khong bi cat giua")
    void keepsCodeFenceIntact() {
        String markdown = """
                # Huong dan

                ## Vi du

                ```sql
                SELECT *
                  FROM rag_chunks
                 WHERE category = 'nhan-su';
                ```
                """;
        List<MarkdownChunker.Chunk> chunks = chunker.chunk(markdown);
        boolean intact = chunks.stream().anyMatch(c ->
                c.content().contains("SELECT") && c.content().contains("nhan-su"));
        assertTrue(intact, "khoi code phai nam tron mot chunk");
    }

    @Test
    @DisplayName("Text dung de nhung co gan duong dan heading -> chunk khong mat goc")
    void embedTextCarriesHeadingPath() {
        String markdown = "# A\n\n## B\n\nNoi dung mot doan van du dai de tao ra mot chunk.\n";
        MarkdownChunker.Chunk chunk = chunker.chunk(markdown).get(0);
        assertTrue(chunk.embedText(null).startsWith("A > B"));
        assertTrue(chunk.embedText("Ngu canh bo sung").contains("Ngu canh bo sung"));
    }

    @Test
    @DisplayName("Tai lieu rong hoac null khong lam no")
    void handlesEmptyInput() {
        assertTrue(chunker.chunk(null).isEmpty());
        assertTrue(chunker.chunk("").isEmpty());
        assertTrue(chunker.chunk("   \n\n  ").isEmpty());
    }

    @Test
    @DisplayName("Khu trung chunk giong nhau trong cung tai lieu")
    void dedupesWithinDocument() {
        String repeated = ("Dieu khoan nay duoc lap lai y nguyen o nhieu muc khac nhau trong tai lieu, "
                + "va no du dai de tao thanh mot chunk rieng biet chu khong bi gop vao chunk khac. ")
                .repeat(6);
        String markdown = "# T\n\n## A\n\n" + repeated + "\n\n## B\n\n" + repeated + "\n";

        props.getChunking().setDedupeWithinDocument(true);
        long withDedupe = chunker.chunk(markdown).size();

        props.getChunking().setDedupeWithinDocument(false);
        long withoutDedupe = chunker.chunk(markdown).size();

        assertTrue(withDedupe < withoutDedupe,
                "bat dedupe phai cho ra it chunk hon: " + withDedupe + " vs " + withoutDedupe);
    }
}
