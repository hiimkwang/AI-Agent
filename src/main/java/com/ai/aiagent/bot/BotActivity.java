package com.ai.aiagent.bot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record BotActivity(
        String type,
        String id,
        String text,
        String conversationId,
        String scope,
        String fromId,
        String fromName,
        String aadObjectId,
        String tenantId,
        String teamAadGroupId,
        String channelId,
        String serviceUrl,
        String recipientId,
        String locale,
        List<String> membersAdded
) {

    public boolean isMessage() {
        return "message".equalsIgnoreCase(type);
    }

    public boolean isBotAdded() {
        return "conversationUpdate".equalsIgnoreCase(type)
                && recipientId != null
                && membersAdded.stream().anyMatch(recipientId::equalsIgnoreCase);
    }

    public boolean isPersonal() {
        return "personal".equalsIgnoreCase(scope);
    }

    public boolean isChannel() {
        return "channel".equalsIgnoreCase(scope);
    }

    public static BotActivity from(JsonNode root) {
        JsonNode channelData = root.path("channelData");
        String scope = text(root.path("conversation").path("conversationType"));
        if (scope == null) {
            scope = channelData.path("team").isMissingNode() ? "personal" : "channel";
        }

        List<String> membersAdded = new ArrayList<>();
        for (JsonNode member : root.path("membersAdded")) {
            String id = text(member.path("id"));
            if (id != null) membersAdded.add(id);
        }

        return new BotActivity(
                text(root.path("type")),
                text(root.path("id")),
                cleanText(root.path("text").asText("")),
                text(root.path("conversation").path("id")),
                scope.toLowerCase(Locale.ROOT),
                text(root.path("from").path("id")),
                text(root.path("from").path("name")),
                lower(text(root.path("from").path("aadObjectId"))),
                lower(firstNonNull(
                        text(channelData.path("tenant").path("id")),
                        text(root.path("conversation").path("tenantId")))),
                lower(text(channelData.path("team").path("aadGroupId"))),
                text(channelData.path("channel").path("id")),
                text(root.path("serviceUrl")),
                text(root.path("recipient").path("id")),
                text(root.path("locale")),
                List.copyOf(membersAdded));
    }

    static String cleanText(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("(?i)<at>.*?</at>", " ")
                .replaceAll("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private static String text(JsonNode node) {
        String v = node.asText(null);
        return v == null || v.isBlank() ? null : v.strip();
    }

    private static String lower(String v) {
        return v == null ? null : v.toLowerCase(Locale.ROOT);
    }

    private static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
