package com.ai.aiagent.platform;

import java.util.LinkedHashSet;
import java.util.Set;

public final class PlatformModels {

    private PlatformModels() {
    }

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

        public boolean usableBy(Set<String> entraGroups, String entraObjectId) {
            if (audienceGroups.isEmpty() && audienceUsers.isEmpty()) return true;
            if (entraObjectId != null && audienceUsers.contains(entraObjectId)) return true;
            return audienceGroups.stream().anyMatch(entraGroups::contains);
        }
    }

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

        public int specificity() {
            return channelId == null ? 0 : 1;
        }
    }

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

    /**
     * Permission to create collections under a slug prefix. Separate from {@link Grant}
     * because a prefix has no scope id, and widening the unique key on rag_grants would
     * mean dropping a constraint that protects real data.
     */
    public record NamespaceGrant(
            Long id,
            String principalType,
            String principalId,
            String slugPrefix,
            int maxCollections,
            String displayName
    ) {
        /** {@code phap-che} owns {@code phap-che} and {@code phap-che-*}, nothing else. */
        public boolean covers(String slug) {
            if (slug == null || slugPrefix == null) return false;
            String s = slug.strip().toLowerCase();
            String p = slugPrefix.strip().toLowerCase();
            return s.equals(p) || s.startsWith(p + "-");
        }
    }

    static Set<String> lower(Set<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) out.add(v.strip().toLowerCase());
        }
        return out;
    }
}
