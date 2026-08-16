package com.ai.aiagent.platform;

import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformModels.ChannelBinding;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Cau hinh nen tang nhieu bot, giu san trong bo nho.
 *
 * Cac bang V3 rat nho (hang chuc dong) nhung duoc doc o MOI tin nhan va MOI cau hoi -
 * doc DB moi lan la lang phi. Ban chup duoc lam moi khi co thay doi, va dinh ky moi
 * phut de nhieu ban chay cung dong bo voi nhau.
 *
 * KHONG cache theo nguoi dung o day: thanh vien nhom Entra da co cache rieng trong
 * {@code EntraScopeService}. Gop hai vong doi cache lai se sinh ra truong hop "doi quyen
 * roi ma cho nay da moi, cho kia con cu".
 */
@Service
@Slf4j
public class PlatformService {

    /** Anh chup nhat quan tai mot thoi diem. Bat bien nen doc duoc tu nhieu luong. */
    public record Snapshot(
            List<CollectionDef> collections,
            List<BotDef> bots,
            List<ChannelBinding> bindings
    ) {
        public Optional<CollectionDef> collection(String slug) {
            return collections.stream()
                    .filter(c -> c.slug().equalsIgnoreCase(slug))
                    .findFirst();
        }

        public Optional<BotDef> bot(String slug) {
            return bots.stream().filter(b -> b.slug().equalsIgnoreCase(slug)).findFirst();
        }

        public Optional<BotDef> botById(long id) {
            return bots.stream().filter(b -> b.id() == id).findFirst();
        }
    }

    private final PlatformRepository repository;
    private volatile Snapshot snapshot = new Snapshot(List.of(), List.of(), List.of());

    public PlatformService(PlatformRepository repository) {
        this.repository = repository;
        refreshQuietly();
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    /**
     * Lam moi dinh ky.
     *
     * Can cho trien khai nhieu ban chay: mot quan tri vien doi cau hinh o ban A thi ban B
     * phai thay trong vong mot phut, chu khong doi den lan khoi dong lai.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void refreshScheduled() {
        refreshQuietly();
    }

    public void refresh() {
        snapshot = new Snapshot(
                repository.collections(),
                repository.bots(),
                repository.channelBindings());
    }

    private void refreshQuietly() {
        try {
            refresh();
        } catch (Exception e) {
            // Luc khoi dong, Flyway co the chua chay xong hoac DB chua san sang. Giu ban
            // chup rong va thu lai o lan lam moi sau - khong duoc lam chet ung dung.
            log.warn("Chua nap duoc cau hinh nen tang ({}). Se thu lai sau 1 phut.",
                    e.getMessage());
        }
    }

    // ============================================================ Truy van quyen

    /**
     * Cac collection ma mot nguoi doc duoc, theo nhom Entra cua ho.
     *
     * MAC DINH TU CHOI: collection chua gan nhom nao thi khong ai doc duoc (tru ADMIN).
     * Day la chu y - mot collection vua tao ma mac dinh ai cung doc duoc la kieu mac dinh
     * sai o cho ton kem nhat.
     */
    public Set<String> readableSlugs(Set<String> entraGroups) {
        Set<String> out = new LinkedHashSet<>();
        if (entraGroups == null || entraGroups.isEmpty()) return out;
        for (CollectionDef c : snapshot.collections()) {
            if (c.isActive() && c.readableBy(entraGroups)) {
                out.add(c.slug());
            }
        }
        return out;
    }

    /** Cac collection duoc phep tra loi trong channel Teams. */
    public Set<String> channelAllowedSlugs() {
        Set<String> out = new LinkedHashSet<>();
        for (CollectionDef c : snapshot.collections()) {
            if (c.isActive() && c.channelAllowed()) out.add(c.slug());
        }
        return out;
    }

    /** True khi chua ai cau hinh ACL cho collection nao - dung de canh bao tren giao dien. */
    public boolean hasNoAcl() {
        return snapshot.collections().stream().allMatch(c -> c.aclGroups().isEmpty());
    }

    // ============================================================ Dinh tuyen bot

    /**
     * Tim bot phuc vu mot cuoc tro chuyen, theo thu tu uu tien:
     *
     *   1. {@code teamsAppId} - moi bot mot Azure Bot rieng (cach 2)
     *   2. rang buoc channel cu the, roi rang buoc ca team (cach 1)
     *   3. bot mac dinh
     *
     * Tra ve rong khi chua co bot nao dang hoat dong.
     */
    public Optional<BotDef> resolveBot(String teamsAppId, String teamAadGroupId, String channelId) {
        Snapshot current = snapshot;

        if (teamsAppId != null && !teamsAppId.isBlank()) {
            Optional<BotDef> byApp = current.bots().stream()
                    .filter(BotDef::isActive)
                    .filter(b -> b.teamsAppId() != null
                            && matchesAppId(b.teamsAppId(), teamsAppId))
                    .findFirst();
            if (byApp.isPresent()) return byApp;
        }

        if (teamAadGroupId != null) {
            Optional<ChannelBinding> binding = current.bindings().stream()
                    .filter(b -> b.matches(teamAadGroupId, channelId))
                    // Rang buoc den dung channel thang rang buoc ca team
                    .max(Comparator.comparingInt(ChannelBinding::specificity));
            if (binding.isPresent()) {
                Optional<BotDef> bound = current.botById(binding.get().botId())
                        .filter(BotDef::isActive);
                if (bound.isPresent()) return bound;
            }
        }

        return current.bots().stream()
                .filter(BotDef::isActive)
                .filter(BotDef::isDefault)
                .findFirst();
    }

    /**
     * {@code activity.recipient.id} cua Teams co dang {@code 28:<app-id>}, con
     * {@code teams_app_id} luu tran. So sanh phai bo tien to, neu khong dinh tuyen theo
     * cach 2 se khong bao gio khop - va bieu hien la "moi bot deu tra loi giong nhau".
     */
    static boolean matchesAppId(String configured, String recipientId) {
        String a = configured.strip().toLowerCase(Locale.ROOT);
        String b = recipientId.strip().toLowerCase(Locale.ROOT);
        int colon = b.indexOf(':');
        if (colon >= 0) b = b.substring(colon + 1);
        return a.equals(b);
    }
}
