package com.ai.aiagent.bot;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Mot Activity cua Bot Framework, da boc ra nhung truong ta thuc su dung.
 *
 * Co y KHONG anh xa toan bo schema Activity (rat lon va hay doi): chi lay dung nhung gi
 * can, doc thang tu JSON. Cung ly do voi {@code GeminiLlmClient} goi REST tay - phu thuoc
 * mot SDK it duoc cap nhat ton kem hon la doc vai truong JSON.
 *
 * @param type           {@code message}, {@code conversationUpdate}, {@code invoke}...
 * @param text           noi dung tin nhan, DA bo phan mention bot
 * @param conversationId dinh danh cuoc tro chuyen - dung luon lam conversationId cua RAG
 *                       nen ngu canh hoi thoai duoc giu dung theo tung cuoc tro chuyen
 * @param scope          {@code personal} (chat rieng) | {@code channel} | {@code groupChat}.
 *                       QUYET DINH PHAM VI TRA LOI - xem {@link TeamsBotService}
 * @param aadObjectId    objectId Entra cua nguoi gui. Day la thu ma Outgoing Webhook cu
 *                       khong co, va la ly do phai chuyen sang bot that
 * @param teamAadGroupId objectId cua Team chua channel; null khi chat rieng
 * @param serviceUrl     dia chi de goi nguoc lai Bot Framework; PHAI khop claim trong token
 * @param membersAdded   id cac thanh vien vua duoc them; dung de biet BOT vua duoc cai
 */
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

    /**
     * CHINH BOT vua duoc cai vao cuoc tro chuyen - dip duy nhat nen gui the chao.
     *
     * Khong duoc chao moi {@code conversationUpdate}: trong mot Team, moi lan co nguoi
     * moi vao channel deu sinh su kien nay, va bot se spam loi chao.
     */
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
            // Chat rieng khong co conversationType trong mot so phien ban payload;
            // suy tu viec co team hay khong.
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

    /**
     * Teams chen mention dang {@code <at>Ten bot</at>} vao dau tin nhan trong channel.
     * Khong bo di thi ten bot lot vao cau truy van va lam nhieu ket qua tim kiem.
     */
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
