package com.ai.aiagent.platform;

import com.ai.aiagent.platform.PlatformModels.BotDef;
import com.ai.aiagent.platform.PlatformModels.ChannelBinding;
import com.ai.aiagent.platform.PlatformModels.CollectionDef;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class PlatformService {

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
            log.warn("Could not load the platform configuration ({}). Retrying in one minute.",
                    e.getMessage());
        }
    }

    /**
     * Declare a collection for {@code slug} unless one already exists.
     *
     * <p>{@code rag_collections.slug} IS the {@code category} column, but nothing in the database
     * enforces that a category has a matching collection row. A folder ingest happily writes
     * categories nobody declared, and the documents then land in a state where only an admin can
     * read them - silently, because retrieval matches the category against the caller's collection
     * slugs and an undeclared category matches none.
     *
     * <p>The new collection is created with an <strong>empty ACL, which means closed</strong>: this
     * only ends the orphan state, it grants nobody access. Someone still has to pick the Entra
     * groups that may read it.
     *
     * @return true when a collection was actually created
     */
    public boolean ensureCollection(String slug, String createdBy) {
        if (slug == null || slug.isBlank()) return false;
        String clean = slug.trim();
        if (snapshot.collection(clean).isPresent()) return false;
        try {
            repository.createCollection(clean, clean, "Tự khai khi nạp tài liệu", false, createdBy);
            refresh();
            log.info("Declared collection '{}' for an otherwise orphaned category (ACL empty, "
                    + "so it stays closed until someone grants read access).", clean);
            return true;
        } catch (DuplicateKeyException e) {
            // Two ingest workers hit the same new folder at once; the other one won.
            refresh();
            return false;
        }
    }

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

    public Set<String> channelAllowedSlugs() {
        Set<String> out = new LinkedHashSet<>();
        for (CollectionDef c : snapshot.collections()) {
            if (c.isActive() && c.channelAllowed()) out.add(c.slug());
        }
        return out;
    }

    /** Documents nobody but an admin can read, because they carry no category at all. */
    public List<String> uncategorizedDocuments() {
        return repository.uncategorizedDocuments();
    }

    /** Categories on documents that no collection declares - same invisibility, different cause. */
    public List<String> orphanCategories() {
        return repository.orphanCategories();
    }

    public boolean hasNoAcl() {
        return snapshot.collections().stream().allMatch(c -> c.aclGroups().isEmpty());
    }

    public Optional<BotDef> resolveBot(String teamsAppId, String teamAadGroupId, String channelId) {
        return resolveBot(teamsAppId, teamAadGroupId, channelId, Set.of(), null);
    }

    /**
     * @param askerGroups     nhóm Entra của người hỏi; để rỗng thì không định tuyến theo người
     * @param askerObjectId   objectId của người hỏi
     */
    public Optional<BotDef> resolveBot(String teamsAppId, String teamAadGroupId, String channelId,
                                       Set<String> askerGroups, String askerObjectId) {
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
                    .max(Comparator.comparingInt(ChannelBinding::specificity));
            if (binding.isPresent()) {
                Optional<BotDef> bound = current.botById(binding.get().botId())
                        .filter(BotDef::isActive);
                if (bound.isPresent()) return bound;
            }
        }

        Optional<BotDef> byAudience = botForAsker(current, askerGroups, askerObjectId);
        if (byAudience.isPresent()) return byAudience;

        return current.bots().stream()
                .filter(BotDef::isActive)
                .filter(BotDef::isDefault)
                .findFirst();
    }

    /**
     * Chọn trợ lý theo đối tượng sử dụng của chính người hỏi — cách để mỗi phòng có một trợ lý
     * riêng trong chat riêng mà không phải tạo Azure Bot mới cho từng phòng.
     *
     * <p>Chỉ xét bot ĐÃ khai đối tượng: đối tượng rỗng nghĩa là "ai cũng dùng được", nếu coi đó
     * là khớp thì mọi bot đều khớp mọi người và việc định tuyến thành ngẫu nhiên.
     *
     * <p>Người ở hai phòng sẽ khớp nhiều bot, nên thứ tự phải xác định được, không phụ thuộc thứ
     * tự trong danh sách: khớp theo <em>người</em> thắng khớp theo <em>nhóm</em>; sau đó bot có
     * đối tượng hẹp hơn thắng; hoà thì lấy slug nhỏ hơn.
     */
    static Optional<BotDef> botForAsker(Snapshot current, Set<String> askerGroups,
                                        String askerObjectId) {
        Set<String> groups = askerGroups == null ? Set.of() : askerGroups;
        if (groups.isEmpty() && askerObjectId == null) return Optional.empty();

        return current.bots().stream()
                .filter(BotDef::isActive)
                .filter(b -> !b.audienceGroups().isEmpty() || !b.audienceUsers().isEmpty())
                .filter(b -> b.usableBy(groups, askerObjectId))
                .min(Comparator
                        .comparingInt((BotDef b) -> askerObjectId != null
                                && b.audienceUsers().contains(askerObjectId) ? 0 : 1)
                        .thenComparingInt(b -> b.audienceGroups().size() + b.audienceUsers().size())
                        .thenComparing(BotDef::slug));
    }

    static boolean matchesAppId(String configured, String recipientId) {
        String a = configured.strip().toLowerCase(Locale.ROOT);
        String b = recipientId.strip().toLowerCase(Locale.ROOT);
        int colon = b.indexOf(':');
        if (colon >= 0) b = b.substring(colon + 1);
        return a.equals(b);
    }
}
