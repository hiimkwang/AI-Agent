package com.ai.aiagent.bot;

import com.ai.aiagent.chat.ChatDtos.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ai.aiagent.store.StoreModels.Citation;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Dung the Adaptive Card cho cau tra loi.
 *
 * Vi sao dung the thay vi tin nhan van ban thuan: nguon trich dan phai NHIN THAY va
 * KIEM CHUNG duoc. Voi tai lieu noi quy, cau tra loi khong kem can cu thi khong dung
 * duoc de lam viec - nguoi doc van phai di hoi lai. The cho phep tach ro phan tra loi
 * va phan can cu, va hien doan trich de nguoi doc tu doi chieu.
 *
 * Phien ban 1.4: moi client Teams dang duoc ho tro deu ve duoc.
 */
@Component
public class AdaptiveCards {

    private final ObjectMapper mapper;

    public AdaptiveCards(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * @param maxCitations so nguon toi da hien; con lai gom vao mot dong "va N nguon khac"
     *                     de the khong dai qua man hinh dien thoai
     */
    public JsonNode answer(ChatResponse response, int maxCitations) {
        ObjectNode card = base();
        ArrayNode body = (ArrayNode) card.get("body");

        body.add(text(response.answer(), true, "default"));

        List<Citation> citations = response.citations();
        if (!response.abstained() && citations != null && !citations.isEmpty()) {
            body.add(separatorLabel("Nguồn"));
            int shown = Math.min(maxCitations, citations.size());
            for (int i = 0; i < shown; i++) {
                body.add(citation(citations.get(i)));
            }
            if (citations.size() > shown) {
                body.add(text("_… và " + (citations.size() - shown) + " nguồn khác_",
                        true, "small"));
            }
        }
        return card;
    }

    /** The chao khi bot duoc cai vao cuoc tro chuyen hoac khi nguoi dung go /help. */
    public JsonNode greeting(String message) {
        ObjectNode card = base();
        ArrayNode body = (ArrayNode) card.get("body");
        body.add(text("Trợ lý tài liệu nội bộ", true, "default").put("weight", "bolder")
                .put("size", "medium"));
        body.add(text(message, true, "default"));
        return card;
    }

    /**
     * The tu choi vi thieu quyen.
     *
     * Noi RO la do quyen chu khong noi "khong tim thay tai lieu" - neu khong nguoi dung
     * se hoi di hoi lai mai. Nhung KHONG neu ten tai lieu hay phong ban ho khong duoc
     * doc, vi ban than danh sach do cung la thong tin.
     */
    public JsonNode denied(String message) {
        ObjectNode card = base();
        ArrayNode body = (ArrayNode) card.get("body");
        body.add(text("Bạn chưa có quyền dùng chức năng này", true, "attention")
                .put("weight", "bolder"));
        body.add(text(message, true, "default"));
        return card;
    }

    // ============================================================ Noi bo

    private ObjectNode base() {
        ObjectNode card = mapper.createObjectNode();
        card.put("type", "AdaptiveCard");
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("version", "1.4");
        card.set("body", mapper.createArrayNode());
        return card;
    }

    private ObjectNode text(String value, boolean wrap, String color) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "TextBlock");
        node.put("text", value == null ? "" : value);
        node.put("wrap", wrap);
        if ("small".equals(color)) {
            node.put("size", "small");
            node.put("isSubtle", true);
        } else if (!"default".equals(color)) {
            node.put("color", color);
        }
        return node;
    }

    private ObjectNode separatorLabel(String label) {
        ObjectNode node = text(label, false, "small");
        node.put("separator", true);
        node.put("weight", "bolder");
        node.put("spacing", "medium");
        return node;
    }

    private ObjectNode citation(Citation c) {
        StringBuilder line = new StringBuilder("**").append(c.rank()).append(". ")
                .append(escape(c.fileName())).append("**");
        if (c.headingPath() != null && !c.headingPath().isBlank()) {
            line.append(" — ").append(escape(c.headingPath()));
        }
        if (c.snippet() != null && !c.snippet().isBlank()) {
            line.append("\n\n").append(escape(abbreviate(c.snippet())));
        }
        ObjectNode node = text(line.toString(), true, "small");
        node.put("spacing", "small");
        return node;
    }

    private static String abbreviate(String s) {
        String flat = s.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "…";
    }

    /**
     * Markdown cua Adaptive Card khong co ranh gioi giua noi dung va cu phap, nen mot
     * ten file chua {@code *} hay {@code _} co the pha vo dinh dang the. Thoat cac ky
     * tu do - cung tinh than voi {@code PromptBuilder.neutralize} o phia prompt.
     */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("*", "\\*").replace("_", "\\_").replace("#", "\\#");
    }
}
