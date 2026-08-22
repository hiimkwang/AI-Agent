package com.ai.aiagent.bot;

import com.ai.aiagent.chat.ChatDtos.ChatResponse;
import com.ai.aiagent.store.StoreModels.Citation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdaptiveCardsTest {

    private final AdaptiveCards cards = new AdaptiveCards(new ObjectMapper());

    private static ChatResponse answer(String text, List<Citation> citations) {
        return new ChatResponse(text, citations, false, null, "ANTHROPIC", "m",
                null, 0L, null, null, "c1", null, List.of());
    }

    private static Citation citation(int rank) {
        return new Citation(rank, (long) rank, "quy-che-" + rank + ".pdf",
                "Điều " + rank, "trích đoạn " + rank, 0.9, rank);
    }

    // Chunking prefixes the document identity, so the raw heading path repeats the file name.
    @Test
    void headingPathDropsTheRepeatedFileName() {
        String path = AdaptiveCards.trimHeadingPath(
                "MobileApp_Front_2022-04-13_TLPT_v1_APPROVED > Phân cấp và vị trí các chức năng"
                        + " > 5.4.1. Tab Đặt lệnh",
                "MobileApp_Front_2022-04-13_TLPT_v1_APPROVED.docx");

        assertThat(path).isEqualTo("Phân cấp và vị trí các chức năng › 5.4.1. Tab Đặt lệnh");
    }

    @Test
    void headingPathKeepsSegmentsThatAreNotTheFileName() {
        assertThat(AdaptiveCards.trimHeadingPath("Điều 12 > Nghỉ hằng năm", "quy-che.pdf"))
                .isEqualTo("Điều 12 › Nghỉ hằng năm");
        assertThat(AdaptiveCards.trimHeadingPath(null, "x.pdf")).isEmpty();
    }

    // The snippet is the raw chunk and starts with the very headings already shown as the path;
    // printing it unchanged shows the same words three times on one card.
    @Test
    void snippetDropsLeadingHeadingsAndIsOmittedWhenNothingIsLeft() {
        assertThat(AdaptiveCards.cleanSnippet(
                "## MobileApp_Front > 5.4.1. Tab Đặt lệnh\n### Loại lệnh")).isNull();

        String kept = AdaptiveCards.cleanSnippet("""
                ## Tab Đặt lệnh
                Loại lệnh là ATO, ATC, MTL, MOK, MAK, PLO, MP.
                Giá mặc định hiển thị là ATO.""");
        assertThat(kept).startsWith("Loại lệnh là ATO").doesNotContain("#");
    }

    @Test
    void snippetIsFlattenedAndCut() {
        String kept = AdaptiveCards.cleanSnippet(("x".repeat(30) + "\n").repeat(20));
        assertThat(kept).hasSize(161).endsWith("…").doesNotContain("\n");
    }

    // The file name must not be printed twice in a row on the same citation line.
    @Test
    void citationDoesNotRepeatTheFileName() {
        Citation c = new Citation(1, 1L, "Lenhdieukien_BSC.docx",
                "Lenhdieukien_BSC > BỔ SUNG TÍNH NĂNG > Back- Thêm API",
                "## Lenhdieukien_BSC > BỔ SUNG TÍNH NĂNG", 0.9, 1);
        JsonNode card = cards.answer(answer("Có.", List.of(c)), 4, 12_000);

        String line = card.path("body").get(2).path("text").asText();
        // escape() puts a backslash before '_', so match on the unescaped part of the name.
        assertThat(line).contains("Lenhdieukien").contains(".docx")
                .contains("BỔ SUNG TÍNH NĂNG");
        assertThat(line.split("Lenhdieukien", -1))
                .as("ten file chi duoc xuat hien mot lan: %s", line)
                .hasSize(2);
        assertThat(line).doesNotContain("#");
    }

    @Test
    void shortAnswerIsUntouched() {
        JsonNode card = cards.answer(answer("Được nghỉ 12 ngày.", List.of()), 4, 12_000);
        assertThat(card.path("body").get(0).path("text").asText())
                .isEqualTo("Được nghỉ 12 ngày.");
        assertThat(card.path("body")).hasSize(1);
    }

    // A card over the Teams activity size limit is dropped whole: the user sees silence.
    @Test
    void longAnswerIsClampedAndSaysSo() {
        String long_ = "a".repeat(50) + " " + "b".repeat(200);
        JsonNode card = cards.answer(answer(long_, List.of()), 4, 100);

        String shown = card.path("body").get(0).path("text").asText();
        assertThat(shown).hasSizeLessThanOrEqualTo(101).endsWith("…");
        assertThat(card.path("body").get(1).path("text").asText()).contains("cắt bớt");
    }

    @Test
    void clampPrefersAWordBoundary() {
        assertThat(AdaptiveCards.clamp("mot hai ba bon nam", 12)).isEqualTo("mot hai ba…");
    }

    @Test
    void zeroMeansNoLimit() {
        String text = "x".repeat(5_000);
        assertThat(AdaptiveCards.clamp(text, 0)).isEqualTo(text);
    }

    @Test
    void citationsSurviveClamping() {
        JsonNode card = cards.answer(answer("y".repeat(500), List.of(citation(1), citation(2))),
                4, 100);
        String flat = card.toString();
        assertThat(flat).contains("Nguồn").contains("quy-che-1.pdf").contains("quy-che-2.pdf");
    }
}
