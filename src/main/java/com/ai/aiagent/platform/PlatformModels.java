package com.ai.aiagent.platform;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Model cua nen tang nhieu bot: collection (tap tai lieu) va bot.
 *
 * Gom vao mot file cho de doi chieu voi V3__bot_platform.sql, cung quy uoc voi
 * {@code StoreModels}.
 */
public final class PlatformModels {

    private PlatformModels() {
    }

    /**
     * Mot tap tai lieu.
     *
     * @param slug           TRUNG voi cot {@code category} cua rag_documents/rag_chunks -
     *                       xem ghi chu dau file migration V3
     * @param channelAllowed co duoc tra loi trong channel Teams khong. Mac dinh false:
     *                       cau tra loi trong channel hien ra cho moi thanh vien channel
     * @param aclGroups      objectId cac nhom Entra doc duoc; RONG = khong ai doc duoc
     *                       (tru ADMIN), day la mac dinh tu choi chu khong phai loi cau hinh
     */
    public record CollectionDef(
            Long id,
            String slug,
            String name,
            String description,
            boolean channelAllowed,
            String status,
            Set<String> aclGroups,
            long documentCount
    ) {
        public boolean isActive() {
            return status == null || "ACTIVE".equalsIgnoreCase(status);
        }

        public boolean readableBy(Set<String> entraGroups) {
            if (aclGroups.isEmpty()) return false;
            return aclGroups.stream().anyMatch(entraGroups::contains);
        }
    }

    /**
     * Mot bot logic.
     *
     * @param teamsAppId      dinh tuyen theo {@code activity.recipient.id} khi moi bot co
     *                        mot Azure Bot rieng; null khi dung chung mot Azure Bot
     * @param audienceGroups  ai duoc DUNG bot; rong = mo cho moi nguoi da xac thuc
     *                        (khac han ACL collection, xem {@link CollectionDef#aclGroups})
     * @param collectionSlugs cac tap tai lieu bot nay duoc doc
     */
    public record BotDef(
            Long id,
            String slug,
            String displayName,
            String description,
            String teamsAppId,
            boolean isDefault,
            String personaPrompt,
            String greeting,
            String llmProvider,
            String llmModel,
            String status,
            Set<String> collectionSlugs,
            Set<String> audienceGroups,
            Set<String> audienceUsers
    ) {
        public boolean isActive() {
            return status == null || "ACTIVE".equalsIgnoreCase(status);
        }

        /**
         * Doi tuong su dung de RONG nghia la MO cho moi nguoi da xac thuc.
         *
         * Co y khac voi ACL collection (rong = dong). Ly do: cam dung bot khong bao ve
         * du lieu - du lieu duoc bao ve boi ACL collection. Bat quan tri vien phai khai
         * doi tuong cho moi bot chi de bot chay duoc la thu tuc thua.
         */
        public boolean usableBy(Set<String> entraGroups, String entraObjectId) {
            if (audienceGroups.isEmpty() && audienceUsers.isEmpty()) return true;
            if (entraObjectId != null && audienceUsers.contains(entraObjectId)) return true;
            return audienceGroups.stream().anyMatch(entraGroups::contains);
        }
    }

    /** Rang buoc Team/channel Teams -> bot. */
    public record ChannelBinding(
            Long id,
            Long botId,
            String botSlug,
            String teamAadGroupId,
            String channelId
    ) {
        public boolean matches(String team, String channel) {
            if (team == null || !team.equalsIgnoreCase(teamAadGroupId)) return false;
            return channelId == null || channelId.equalsIgnoreCase(channel);
        }

        /** Rang buoc cu the den channel duoc uu tien hon rang buoc ca team. */
        public int specificity() {
            return channelId == null ? 0 : 1;
        }
    }

    /** Quyen quan tri muc min tren mot bot hoac mot collection. */
    public record Grant(
            Long id,
            String principalType,
            String principalId,
            String scopeType,
            long scopeId,
            String role,
            String displayName
    ) {
    }

    static Set<String> lower(Set<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) out.add(v.strip().toLowerCase());
        }
        return out;
    }
}
