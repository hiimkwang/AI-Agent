package com.ai.aiagent.ingest;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which category a file ends up in decides who can ever read it, and getting it wrong shows up
 * only much later - the document is ingested fine and an admin sees it on the web while every
 * normal user is told "khong tim thay". So the precedence is pinned here.
 */
class FolderCategoryPrecedenceTest {

    private static final Path ROOT = Path.of("/data/tai-lieu");

    private static String categoryOf(String relativeFile, Map<String, String> overrides,
                                     boolean derive) {
        return IngestionJobService.categoryFor(ROOT, ROOT.resolve(relativeFile), overrides, derive);
    }

    @Test
    @DisplayName("Nguoi van hanh sua tren man hinh thi thang tat ca")
    void confirmedOverrideWins() {
        assertThat(categoryOf("ptpm/Core FDS/x.docx",
                Map.of("ptpm/Core FDS", "ptpm-core-fds-2026"), true))
                .isEqualTo("ptpm-core-fds-2026");
    }

    @Test
    @DisplayName("Khong sua thi lay theo ten thu muc")
    void derivedIsUsedWhenNoOverride() {
        assertThat(categoryOf("ptpm/Core FDS/x.docx", Map.of(), true))
                .isEqualTo("ptpm-core-fds");
    }

    @Test
    @DisplayName("Tat suy theo thu muc thi tra null de category cua job duoc ap dung")
    void nullLetsTheJobWideCategoryApply() {
        assertThat(categoryOf("ptpm/Core FDS/x.docx", Map.of(), false)).isNull();
    }

    @Test
    @DisplayName("Override rong bi bo qua, khong bien thanh category rong")
    void blankOverrideIsIgnored() {
        assertThat(categoryOf("ptpm/Algo/x.docx", Map.of("ptpm/Algo", "   "), true))
                .isEqualTo("ptpm-algo");
    }

    @Test
    @DisplayName("Override duoc chuan hoa ve chu thuong")
    void overrideIsLowercased() {
        assertThat(categoryOf("ptpm/Algo/x.docx", Map.of("ptpm/Algo", "  PTPM-Algo "), true))
                .isEqualTo("ptpm-algo");
    }

    @Test
    @DisplayName("Override cua thu muc khac khong ap sai file")
    void overrideAppliesOnlyToItsOwnFolder() {
        Map<String, String> overrides = Map.of("ptpm/Algo", "khac");
        assertThat(categoryOf("ptpm/OMS/x.docx", overrides, true)).isEqualTo("ptpm-oms");
    }

    // Path.toString() would give "ptpm\Core FDS" on Windows, so the key the browser sends back
    // would never match and every override would be silently dropped.
    @Test
    @DisplayName("Khoa thu muc luon dung dau '/', khong phu thuoc he dieu hanh")
    void folderKeyIsAlwaysSlashSeparated() {
        assertThat(IngestionJobService.relativeFolder(ROOT, ROOT.resolve("ptpm/Core FDS/x.docx")))
                .isEqualTo("ptpm/Core FDS");
        assertThat(IngestionJobService.relativeFolder(ROOT, ROOT.resolve("a/b/c/x.docx")))
                .isEqualTo("a/b/c");
        assertThat(IngestionJobService.relativeFolder(ROOT, ROOT.resolve("x.docx"))).isEmpty();
    }
}
