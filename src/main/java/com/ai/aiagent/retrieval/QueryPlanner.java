package com.ai.aiagent.retrieval;

import com.ai.aiagent.config.RagProperties;
import com.ai.aiagent.llm.InternalLlm;
import com.ai.aiagent.store.StoreModels.Turn;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class QueryPlanner {

    public record QueryPlan(String original, String rewritten, List<String> variants,
                             String clarifyingQuestion, List<String> suggestions) {
        public boolean wasRewritten() {
            return !original.equals(rewritten);
        }
    }

    private record Normalized(String rewritten, String clarifyingQuestion,
                              List<String> suggestions) {
    }

    private final InternalLlm internalLlm;
    private final RagProperties props;
    private final GlossaryService glossary;
    private final ObjectMapper mapper = new ObjectMapper();

    public QueryPlanner(InternalLlm internalLlm, RagProperties props, GlossaryService glossary) {
        this.internalLlm = internalLlm;
        this.props = props;
        this.glossary = glossary;
    }

    public QueryPlan plan(String question, List<Turn> history) {
        Normalized normalized = normalize(question, history);
        String rewritten = normalized.rewritten();

        if (normalized.clarifyingQuestion() != null) {
            return new QueryPlan(question, rewritten, List.of(), normalized.clarifyingQuestion(),
                    normalized.suggestions());
        }

        Set<String> variants = new LinkedHashSet<>();
        variants.add(question);
        if (props.getRetrieval().isMultiQueryEnabled() && !rewritten.equals(question)) {
            variants.add(rewritten);
        } else if (!props.getRetrieval().isMultiQueryEnabled()) {
            variants.clear();
            variants.add(rewritten);
        }
        if (props.getRetrieval().isGlossaryEnabled()) {
            String expanded = expandWithGlossary(rewritten);
            if (expanded != null) variants.add(expanded);
        }
        if (props.getRetrieval().isHydeEnabled()) {
            String hyde = hyde(rewritten);
            if (hyde != null && !hyde.isBlank()) variants.add(hyde);
        }
        return new QueryPlan(question, rewritten, new ArrayList<>(variants), null, List.of());
    }

    private String expandWithGlossary(String query) {
        try {
            Set<String> expansions = glossary.expand(query);
            if (expansions.isEmpty()) return null;
            String expanded = query + " " + String.join(" ", expansions);
            log.debug("Glossary expansion: '{}' -> '{}'", query, expanded);
            return expanded;
        } catch (Exception e) {
            log.warn("Glossary expansion failed ({}), skipping this query variant.", e.getMessage());
            return null;
        }
    }

    private Normalized normalize(String question, List<Turn> history) {
        boolean clarifyEnabled = props.getRetrieval().isClarifyAmbiguousEnabled();
        boolean hasHistory = history != null && !history.isEmpty();

        if (!props.getQueryRewrite().isEnabled()) {
            return new Normalized(question, null, List.of());
        }
        if (!clarifyEnabled && !hasHistory) {
            return new Normalized(question, null, List.of());
        }

        String formatted = hasHistory ? formatHistory(history) : "";
        if (!clarifyEnabled && formatted.isBlank()) {
            return new Normalized(question, null, List.of());
        }

        try {
            if (clarifyEnabled) {
                return normalizeWithClarityCheck(question, formatted);
            }
            return new Normalized(rewriteOnly(question, formatted), null, List.of());
        } catch (Exception e) {
            log.warn("Question normalisation failed ({}), using the original question as-is.",
                    e.getMessage());
            return new Normalized(question, null, List.of());
        }
    }

    private Normalized normalizeWithClarityCheck(String question, String formattedHistory) {
        String prompt = glossaryBlock(question) + """
                Ban la buoc CHUAN HOA CAU HOI truoc khi he thong tim kiem tai lieu noi bo.

                Cho LICH SU HOI THOAI (co the rong neu day la tin nhan dau tien) va CAU HOI
                MOI cua nguoi dung, hay:
                1) Neu cau hoi phu thuoc ngu canh truoc (dai tu "no/cai do/vay thi...") thi
                   viet lai thanh mot cau hoi DOC LAP, day du ngu canh. Giu nguyen tieng Viet
                   va GIU LAI moi tu khoa, ten rieng, ma so co trong cau hoi goc. Neu khong can
                   viet lai, giu nguyen cau hoi goc.
                2) Danh gia cau hoi (SAU KHI viet lai) co DU RO RANG de tim tai lieu hay khong.
                   Cau qua ngan, chung chung, thieu chu de cu the (vd "cho toi hoi", "cai do
                   the nao", "oke vay sao", "giup toi voi") duoc coi la CHUA RO. Cau da neu ro
                   chu de/tu khoa (du ngan, vd "Nghi phep bao nhieu ngay?") duoc coi la RO.

                3) Neu CHUA RO, doan 2-3 cau hoi CU THE ma nguoi dung co the dang muon hoi,
                   dua tren phan da hieu duoc. Moi cau phai la mot cau hoi day du, doc lap,
                   tieng Viet, khong qua 90 ky tu. Neu khong doan noi thi tra ve mang rong.

                Tra ve DUY NHAT mot JSON, khong giai thich, dung dinh dang:
                {"rewritten": "cau hoi (da viet lai neu can)", "clear": true hoac false, "question": "cau hoi goi y de nguoi dung noi ro hon, CHI dien khi clear=false, tieng Viet, rong neu clear=true", "options": ["cau hoi cu the 1", "cau hoi cu the 2"]}

                LICH SU HOI THOAI:
                %s

                CAU HOI MOI: %s
                """.formatted(formattedHistory.isBlank() ? "(chua co lich su - day la tin nhan dau tien)"
                : formattedHistory, question);

        String response;
        try {
            response = internalLlm.generate(prompt);
        } catch (Exception e) {
            log.warn("Ambiguity check failed ({}), treating the question as clear enough.",
                    e.getMessage());
            return new Normalized(question, null, List.of());
        }

        String rewritten = question;
        String clarifying = null;
        List<String> options = new ArrayList<>();
        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start >= 0 && end > start) {
                JsonNode node = mapper.readTree(response.substring(start, end + 1));
                String r = node.path("rewritten").asText(question).strip();
                if (!r.isEmpty() && r.length() <= question.length() * 6 + 200) rewritten = r;
                boolean clear = node.path("clear").asBoolean(true);
                if (!clear) {
                    String q = node.path("question").asText("").strip();
                    if (!q.isEmpty()) clarifying = q;
                    // Doc phong thu: thieu truong, sai kieu hay rong deu chi la khong co goi y.
                    JsonNode opts = node.path("options");
                    if (opts.isArray()) {
                        for (JsonNode o : opts) {
                            String text = o.asText("").strip();
                            if (text.isEmpty() || text.length() > 120) continue;
                            if (options.stream().noneMatch(x -> x.equalsIgnoreCase(text))) {
                                options.add(text);
                            }
                            if (options.size() == 3) break;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not parse the normalisation JSON ({}), treating the question as clear "
                            + "enough. Response: {}",
                    e.getMessage(),
                    response == null ? "" : response.substring(0, Math.min(200, response.length())));
        }

        if (clarifying != null) {
            log.debug("Question is ambiguous, asking back: '{}'", clarifying);
        } else if (!rewritten.equals(question)) {
            log.debug("Query rewritten: '{}' -> '{}'", question, rewritten);
        }
        return new Normalized(rewritten, clarifying, List.copyOf(options));
    }

    private String glossaryBlock(String question) {
        if (!props.getRetrieval().isGlossaryEnabled()) return "";
        String hint;
        try {
            hint = glossary.hintFor(question);
        } catch (Exception e) {
            return "";
        }
        if (hint == null || hint.isBlank()) return "";
        return "THUAT NGU NOI BO (dung de hieu dung cau hoi, va nen dung dang day du "
                + "khi viet lai):\n" + hint + "\n";
    }

    private String rewriteOnly(String question, String formattedHistory) {
        String prompt = glossaryBlock(question) + """
                Duoi day la lich su hoi thoai va cau hoi moi nhat cua nguoi dung.
                Hay viet lai CAU HOI MOI thanh mot cau hoi DOC LAP, day du ngu canh, tu hieu
                duoc ma khong can doc lich su (thay cac dai tu "no/cai do/vay..." bang doi
                tuong cu the). Giu nguyen tieng Viet va GIU LAI moi tu khoa, ten rieng, ma so
                co trong cau hoi goc. CHI tra ve cau hoi da viet lai, khong giai thich.

                LICH SU HOI THOAI:
                %s

                CAU HOI MOI: %s

                CAU HOI DOC LAP:
                """.formatted(formattedHistory, question);

        String rewritten = internalLlm.generate(prompt);
        if (rewritten == null || rewritten.isBlank()) return question;
        rewritten = rewritten.strip();
        if (rewritten.length() > question.length() * 6 + 200) return question;
        if (!rewritten.equals(question)) {
            log.debug("Query rewritten: '{}' -> '{}'", question, rewritten);
        }
        return rewritten;
    }

    private String hyde(String question) {
        try {
            String prompt = """
                    Viet mot doan van NGAN (3-4 cau) tra loi cau hoi duoi day theo van phong
                    cua tai lieu quy dinh noi bo cong ty. Khong can dung tuyet doi - muc dich
                    chi la tao mot doan van co tu vung giong tai lieu that. Khong them loi dan.

                    CAU HOI: %s
                    """.formatted(question);
            return internalLlm.generate(prompt);
        } catch (Exception e) {
            log.warn("HyDE generation failed ({}), skipping this query variant.", e.getMessage());
            return null;
        }
    }

    private String formatHistory(List<Turn> history) {
        int maxTurns = Math.max(1, props.getQueryRewrite().getMaxHistoryTurns()) * 2;
        List<Turn> recent = history.size() > maxTurns
                ? history.subList(history.size() - maxTurns, history.size()) : history;

        StringBuilder sb = new StringBuilder();
        for (Turn t : recent) {
            sb.append("user".equals(t.role()) ? "Nguoi dung: " : "Tro ly: ")
                    .append(t.content() == null ? "" : t.content())
                    .append('\n');
        }
        String out = sb.toString().strip();
        int maxChars = props.getQueryRewrite().getMaxHistoryChars();
        if (out.length() > maxChars) {
            out = "[...]\n" + out.substring(out.length() - maxChars);
        }
        return out;
    }
}
