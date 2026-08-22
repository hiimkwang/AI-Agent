package com.ai.aiagent.bot;

import com.ai.aiagent.chat.ChatDtos.ChatResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ai.aiagent.store.StoreModels.Citation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class AdaptiveCards {

    /** Below this a snippet only repeats the heading, so it is dropped instead. */
    private static final int MIN_SNIPPET_CHARS = 40;
    private static final int SNIPPET_CHARS = 160;

    private final ObjectMapper mapper;

    public AdaptiveCards(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode answer(ChatResponse response, int maxCitations, int maxAnswerChars) {
        ObjectNode card = base();
        ArrayNode body = (ArrayNode) card.get("body");

        // Teams caps the size of one activity (~28 KB). Over the cap the whole card is
        // dropped, so the user sees silence - clamp instead and say so.
        String answer = clamp(response.answer(), maxAnswerChars);
        body.add(text(answer, true, "default"));
        if (answer.length() < lengthOf(response.answer())) {
            body.add(text("_Câu trả lời đã được cắt bớt cho vừa thẻ Teams. "
                    + "Xem đầy đủ trên giao diện web._", true, "small"));
        }

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

    public JsonNode greeting(String title, String message) {
        ObjectNode card = base();
        ArrayNode body = (ArrayNode) card.get("body");
        body.add(text(title, true, "default").put("weight", "bolder")
                .put("size", "medium"));
        body.add(text(message, true, "default"));
        return card;
    }

    public JsonNode denied(String message) {
        ObjectNode card = base();
        ArrayNode body = (ArrayNode) card.get("body");
        body.add(text("Bạn chưa có quyền dùng chức năng này", true, "attention")
                .put("weight", "bolder"));
        body.add(text(message, true, "default"));
        return card;
    }

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

        String path = trimHeadingPath(c.headingPath(), c.fileName());
        if (!path.isBlank()) {
            line.append("\n\n").append(escape(path));
        }
        String snippet = cleanSnippet(c.snippet());
        if (snippet != null) {
            line.append("\n\n").append(escape(snippet));
        }
        ObjectNode node = text(line.toString(), true, "small");
        node.put("spacing", "small");
        return node;
    }

    /**
     * Chunking prefixes the document identity, so the heading path usually starts with the file
     * name again - on a Teams card that reads as the file name printed twice in a row.
     */
    static String trimHeadingPath(String headingPath, String fileName) {
        if (headingPath == null || headingPath.isBlank()) return "";
        String stem = normalize(stripExtension(fileName));
        List<String> parts = new ArrayList<>();
        for (String raw : headingPath.split(">")) {
            String part = raw.strip();
            if (part.isEmpty()) continue;
            if (parts.isEmpty() && !stem.isEmpty() && normalize(part).equals(stem)) continue;
            parts.add(part);
        }
        return String.join(" › ", parts);
    }

    /**
     * The snippet is the raw chunk, which starts with the same markdown headings already shown
     * as the path. Strip them; if nothing substantial is left, show no snippet at all rather
     * than repeating the heading a third time.
     */
    static String cleanSnippet(String snippet) {
        if (snippet == null || snippet.isBlank()) return null;
        StringBuilder kept = new StringBuilder();
        for (String rawLine : snippet.split("\\R")) {
            String line = rawLine.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) continue;
            if (kept.length() > 0) kept.append(' ');
            kept.append(line);
        }
        String flat = kept.toString().replaceAll("\\s+", " ").strip();
        if (flat.length() < MIN_SNIPPET_CHARS) return null;
        return flat.length() <= SNIPPET_CHARS ? flat
                : flat.substring(0, SNIPPET_CHARS).stripTrailing() + "…";
    }

    private static String stripExtension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String clamp(String answer, int maxChars) {
        if (answer == null) return "";
        if (maxChars <= 0 || answer.length() <= maxChars) return answer;
        int cut = answer.lastIndexOf(' ', maxChars);
        int at = cut > maxChars / 2 ? cut : maxChars;
        return answer.substring(0, at).stripTrailing() + "…";
    }

    private static int lengthOf(String s) {
        return s == null ? 0 : s.length();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("*", "\\*").replace("_", "\\_").replace("#", "\\#");
    }
}
