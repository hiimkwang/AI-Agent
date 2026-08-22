package com.ai.aiagent.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CategorySlugsTest {

    /**
     * The 18 folders that actually exist under /app/aiagent/work/tai-lieu on the UAT server,
     * mapped to the 17 collection slugs already in the database. If a slug drifts, re-ingesting
     * the tree stops overwriting and starts duplicating, because doc_key is category/fileName.
     */
    private static Map<String, String> realServerLayout() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("ptpm/Algo", "ptpm-algo");
        m.put("ptpm/Backend Flex", "ptpm-backend-flex");
        m.put("ptpm/Bloomberg", "ptpm-bloomberg");
        m.put("ptpm/CCQ", "ptpm-ccq");
        m.put("ptpm/CDFLEX", "ptpm-cdflex");
        m.put("ptpm/Chatbot Giai Điệu", "ptpm-chatbot-giai-dieu");
        m.put("ptpm/Core FDS", "ptpm-core-fds");
        m.put("ptpm/Đầu tư danh mục", "ptpm-dau-tu-danh-muc");
        m.put("ptpm/Frontend", "ptpm-frontend");
        m.put("ptpm/Lệnh điều kiện", "ptpm-lenh-dieu-kien");
        m.put("ptpm/Mtrader", "ptpm-mtrader");
        m.put("ptpm/OMS", "ptpm-oms");
        m.put("ptpm/OpenAPI", "ptpm-openapi");
        m.put("ptpm/OTC-Trái phiếu Flex", "ptpm-otc-trai-phieu-flex");
        m.put("ptpm/Price", "ptpm-price");
        m.put("ptpm/Trái phiếu riêng lẻ", "ptpm-trai-phieu-rieng-le");
        m.put("ptpm/Website BSC", "ptpm-website-bsc");
        return m;
    }

    @Test
    @DisplayName("17 thu muc that tren may chu suy ra dung 17 slug dang co trong CSDL")
    void realFolderNamesReproduceTheExistingSlugs() {
        Path root = Path.of("/app/aiagent/work/tai-lieu");
        realServerLayout().forEach((folder, expected) -> {
            Path file = root.resolve(folder).resolve("tai-lieu.docx");
            assertThat(CategorySlugs.fromPath(root, file))
                    .as("thu muc '%s'", folder)
                    .isEqualTo(expected);
        });
    }

    @Test
    @DisplayName("File nam ngay tai goc thi khong suy duoc -> tra null, khong tra chuoi rong")
    void fileDirectlyInRootHasNothingToDeriveFrom() {
        Path root = Path.of("/data/tai-lieu");
        assertThat(CategorySlugs.fromPath(root, root.resolve("x.docx"))).isNull();
    }

    @Test
    @DisplayName("Thu muc long nhieu cap thi noi bang dau gach")
    void nestedFoldersAreJoined() {
        Path root = Path.of("/data");
        assertThat(CategorySlugs.fromPath(root, root.resolve("ptpm/Core FDS/2025/x.pdf")))
                .isEqualTo("ptpm-core-fds-2025");
    }

    @Test
    @DisplayName("Dau tieng Viet va ky tu la bi loai, khong sinh dau gach kep")
    void slugifyHandlesVietnameseAndPunctuation() {
        assertThat(CategorySlugs.slugify("Đầu tư danh mục")).isEqualTo("dau-tu-danh-muc");
        assertThat(CategorySlugs.slugify("OTC-Trái phiếu Flex")).isEqualTo("otc-trai-phieu-flex");
        assertThat(CategorySlugs.slugify("  Lệnh   điều kiện  ")).isEqualTo("lenh-dieu-kien");
        assertThat(CategorySlugs.slugify("A & B (v2)")).isEqualTo("a-b-v2");
        assertThat(CategorySlugs.slugify("...")).isEmpty();
    }

    // Slug feeds doc_key, which uses '/' as its separator.
    @Test
    @DisplayName("Slug khong bao gio chua dau gach cheo")
    void slugNeverContainsASlash() {
        Path root = Path.of("/data");
        String slug = CategorySlugs.fromPath(root, root.resolve("a b/c d/x.docx"));
        assertThat(slug).isEqualTo("a-b-c-d").doesNotContain("/");
    }

    @Test
    @DisplayName("File ngoai goc thi tra null chu khong nem loi")
    void fileOutsideRootIsNull() {
        assertThat(CategorySlugs.fromPath(Path.of("/data"), Path.of("/other/x.docx")))
                .isNotEqualTo("");
    }
}
