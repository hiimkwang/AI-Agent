package com.ai.aiagent.platform;

import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformService.Snapshot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Định tuyến trợ lý theo người hỏi (Cách B): trong chat riêng không có Team lẫn kênh, nên đây là
 * cách duy nhất để mỗi phòng có trợ lý riêng mà không phải tạo thêm Azure Bot.
 *
 * <p>Người ở hai phòng là trường hợp phải xác định được kết quả — nếu không, cùng một người hỏi
 * hai lần có thể gặp hai trợ lý khác nhau và không ai hiểu vì sao.
 */
class BotForAskerTest {

    private static BotDef bot(String slug, boolean isDefault, Set<String> groups,
                              Set<String> users) {
        return bot(slug, isDefault, groups, users, "ACTIVE");
    }

    private static BotDef bot(String slug, boolean isDefault, Set<String> groups,
                              Set<String> users, String status) {
        return new BotDef((long) slug.hashCode(), slug, slug, null, null, isDefault,
                null, null, null, null, status, Set.of("chung"), groups, users);
    }

    private static Snapshot snapshot(BotDef... bots) {
        return new Snapshot(List.of(), List.of(bots), List.of());
    }

    private static Optional<BotDef> pick(Snapshot s, Set<String> groups, String userId) {
        return PlatformService.botForAsker(s, groups, userId);
    }

    @Test
    @DisplayName("Khong khai doi tuong thi khong tham gia dinh tuyen")
    void botsWithoutAudienceAreNotRouted() {
        Snapshot s = snapshot(bot("chung", true, Set.of(), Set.of()));
        assertThat(pick(s, Set.of("g-nhan-su"), "u1")).isEmpty();
    }

    @Test
    @DisplayName("Khop dung mot bot thi lay bot do")
    void singleMatchWins() {
        Snapshot s = snapshot(
                bot("chung", true, Set.of(), Set.of()),
                bot("nhan-su", false, Set.of("g-nhan-su"), Set.of()),
                bot("ke-toan", false, Set.of("g-ke-toan"), Set.of()));

        assertThat(pick(s, Set.of("g-nhan-su"), "u1")).get()
                .extracting(BotDef::slug).isEqualTo("nhan-su");
    }

    @Test
    @DisplayName("Khong khop bot nao thi tra rong de roi ve bot mac dinh")
    void noMatchFallsThrough() {
        Snapshot s = snapshot(bot("nhan-su", false, Set.of("g-nhan-su"), Set.of()));
        assertThat(pick(s, Set.of("g-khac"), "u1")).isEmpty();
    }

    @Test
    @DisplayName("Khop theo NGUOI thang khop theo NHOM")
    void userMatchBeatsGroupMatch() {
        Snapshot s = snapshot(
                bot("theo-nhom", false, Set.of("g-nhan-su"), Set.of()),
                bot("theo-nguoi", false, Set.of(), Set.of("u1")));

        assertThat(pick(s, Set.of("g-nhan-su"), "u1")).get()
                .extracting(BotDef::slug).isEqualTo("theo-nguoi");
    }

    @Test
    @DisplayName("Nguoi o hai phong: bot co doi tuong HEP hon thang")
    void narrowerAudienceWins() {
        Snapshot s = snapshot(
                bot("rong", false, Set.of("g-a", "g-b", "g-c"), Set.of()),
                bot("hep", false, Set.of("g-b"), Set.of()));

        assertThat(pick(s, Set.of("g-b", "g-c"), "u1")).get()
                .extracting(BotDef::slug).isEqualTo("hep");
    }

    @Test
    @DisplayName("Hoa nhau thi theo slug, khong phu thuoc thu tu trong danh sach")
    void tieIsBrokenBySlugNotByListOrder() {
        BotDef beta = bot("beta", false, Set.of("g-a"), Set.of());
        BotDef alpha = bot("alpha", false, Set.of("g-a"), Set.of());

        assertThat(pick(snapshot(beta, alpha), Set.of("g-a"), "u1")).get()
                .extracting(BotDef::slug).isEqualTo("alpha");
        // Dao thu tu dau vao phai cho cung ket qua.
        assertThat(pick(snapshot(alpha, beta), Set.of("g-a"), "u1")).get()
                .extracting(BotDef::slug).isEqualTo("alpha");
    }

    @Test
    @DisplayName("Bot dang tat khong duoc chon")
    void inactiveBotIsSkipped() {
        BotDef off = bot("tat", false, Set.of("g-a"), Set.of(), "INACTIVE");
        assertThat(pick(snapshot(off), Set.of("g-a"), "u1")).isEmpty();
    }

    @Test
    @DisplayName("Khong biet nguoi hoi thi khong dinh tuyen")
    void unknownAskerIsNotRouted() {
        Snapshot s = snapshot(bot("nhan-su", false, Set.of("g-nhan-su"), Set.of()));
        assertThat(pick(s, Set.of(), null)).isEmpty();
    }
}
