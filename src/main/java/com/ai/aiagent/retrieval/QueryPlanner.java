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

/**
 * Sinh cac BIEN THE TRUY VAN de tim kiem.
 *
 * Sua mot diem yeu cu the cua ban truoc: query rewrite THAY THE cau hoi goc, lam
 * mat cac tu khoa nguoi dung da go (ten rieng, ma so, viet tat). Gio ta truy xuat
 * bang CA HAI roi gop bang RRF - ha tang RRF da co san nen gan nhu mien phi.
 *
 * Ba bien the:
 *   1) cau goc            - luon co, giu nguyen tu khoa nguoi dung
 *   2) cau viet lai       - lam ro dai tu cho cau hoi noi tiep (multi-turn)
 *   3) HyDE (tuy chon)    - mot cau tra loi GIA DINH; nhung cau tra loi gia dinh
 *                           thuong gan tai lieu that hon la cau hoi
 */
@Service
@Slf4j
public class QueryPlanner {

    /**
     * @param original          cau hoi nguoi dung go
     * @param rewritten         cau hoi doc lap (bang original neu khong viet lai)
     * @param variants          tat ca chuoi dung de truy xuat, da khu trung
     * @param clarifyingQuestion cau hoi goi y de nguoi dung lam ro, null neu cau hoi da du ro
     *                          rang (xem {@link #normalize}). Khac null nghia la RagChatService
     *                          nen bo qua retrieval va hoi lai nguoi dung ngay.
     */
    public record QueryPlan(String original, String rewritten, List<String> variants,
                             String clarifyingQuestion) {
        public boolean wasRewritten() {
            return !original.equals(rewritten);
        }
    }

    private record Normalized(String rewritten, String clarifyingQuestion) {
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
            // Cau hoi mo ho: khong can sinh bien the truy xuat, RagChatService se hoi lai ngay
            return new QueryPlan(question, rewritten, List.of(), normalized.clarifyingQuestion());
        }

        Set<String> variants = new LinkedHashSet<>();
        variants.add(question);
        if (props.getRetrieval().isMultiQueryEnabled() && !rewritten.equals(question)) {
            variants.add(rewritten);
        } else if (!props.getRetrieval().isMultiQueryEnabled()) {
            // Khong multi-query: chi dung cau viet lai (hanh vi cu)
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
        return new QueryPlan(question, rewritten, new ArrayList<>(variants), null);
    }

    /**
     * Bien the co mo rong viet tat: "quy dinh margin" -> "quy dinh margin giao dich ky quy".
     *
     * THEM mot bien the chu khong THAY THE cau goc. Neu thay the, cau hoi go tat se mat
     * chinh tu khoa nguoi dung go - ma trong tai lieu noi bo, tu viet tat cung thuong
     * xuat hien nguyen dang. Them bien the thi RRF tu gop hai ket qua, gan nhu mien phi
     * vi ha tang multi-query da co san.
     */
    private String expandWithGlossary(String query) {
        try {
            Set<String> expansions = glossary.expand(query);
            if (expansions.isEmpty()) return null;
            String expanded = query + " " + String.join(" ", expansions);
            log.debug("Mo rong thuat ngu: '{}' -> '{}'", query, expanded);
            return expanded;
        } catch (Exception e) {
            log.warn("Mo rong thuat ngu loi ({}) -> bo qua bien the nay.", e.getMessage());
            return null;
        }
    }

    /**
     * Viet lai cau hoi noi tiep thanh cau hoi doc lap VA danh gia cau hoi co du ro rang
     * de tim tai lieu hay khong - gop CHUNG mot lenh goi LLM (khong ton them chi phi so
     * voi khi chi tach rieng buoc viet lai).
     *
     * Khi {@code retrieval.clarifyAmbiguousEnabled} bat: LUON goi model, KE CA khi chua
     * co lich su - vi cau hoi mo ho co the la tin nhan DAU TIEN cua hoi thoai (vd "cho
     * toi hoi cai nay voi"), khong the phat hien duoc neu chi xet lich su.
     *
     * Khi tat: giu dung hanh vi cu (chi goi model viet lai khi co lich su, khong danh
     * gia mo ho) - nguoi da tat khong bi tang chi phi/do tre mac dinh.
     *
     * Loi o buoc nay khong duoc lam sap ca cau tra loi -> coi nhu cau hoi da du ro rang,
     * dung nguyen cau hoi goc (fallback im lang, dung triet ly chung cua pipeline).
     */
    private Normalized normalize(String question, List<Turn> history) {
        boolean clarifyEnabled = props.getRetrieval().isClarifyAmbiguousEnabled();
        boolean hasHistory = history != null && !history.isEmpty();

        if (!props.getQueryRewrite().isEnabled()) {
            return new Normalized(question, null);
        }
        if (!clarifyEnabled && !hasHistory) {
            return new Normalized(question, null);
        }

        String formatted = hasHistory ? formatHistory(history) : "";
        if (!clarifyEnabled && formatted.isBlank()) {
            return new Normalized(question, null);
        }

        try {
            if (clarifyEnabled) {
                return normalizeWithClarityCheck(question, formatted);
            }
            return new Normalized(rewriteOnly(question, formatted), null);
        } catch (Exception e) {
            log.warn("Chuan hoa cau hoi loi ({}) -> dung cau hoi goc, coi nhu da du ro.",
                    e.getMessage());
            return new Normalized(question, null);
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

                Tra ve DUY NHAT mot JSON, khong giai thich, dung dinh dang:
                {"rewritten": "cau hoi (da viet lai neu can)", "clear": true hoac false, "question": "cau hoi goi y de nguoi dung noi ro hon, CHI dien khi clear=false, tieng Viet, rong neu clear=true"}

                LICH SU HOI THOAI:
                %s

                CAU HOI MOI: %s
                """.formatted(formattedHistory.isBlank() ? "(chua co lich su - day la tin nhan dau tien)"
                : formattedHistory, question);

        String response;
        try {
            response = internalLlm.generate(prompt);
        } catch (Exception e) {
            log.warn("Kiem tra do ro rang cau hoi loi ({}) -> coi nhu da du ro.", e.getMessage());
            return new Normalized(question, null);
        }

        String rewritten = question;
        String clarifying = null;
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
                }
            }
        } catch (Exception e) {
            log.warn("Khong doc duoc JSON chuan hoa cau hoi ({}) -> coi nhu da du ro. Phan hoi: {}",
                    e.getMessage(), response == null ? "" : response.substring(0, Math.min(200, response.length())));
        }

        if (clarifying != null) {
            log.info("Cau hoi mo ho -> hoi lai: '{}'", clarifying);
        } else if (!rewritten.equals(question)) {
            log.debug("Query rewrite: '{}' -> '{}'", question, rewritten);
        }
        return new Normalized(rewritten, clarifying);
    }

    /**
     * Khoi thuat ngu chen len dau prompt viet lai.
     *
     * Muc dich khac voi {@link #expandWithGlossary}: o day la de model viet lai cau hoi
     * BANG DUNG thuat ngu cua tai lieu (giup ca nhanh vector), con kia la mo rong tho
     * cho nhanh full-text. Tra ve chuoi rong khi cau hoi khong chua thuat ngu nao, de
     * khong lam phong prompt mot cach vo ich.
     */
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

    /** Hanh vi cu: chi viet lai, khong danh gia do ro rang (dung khi clarify bi tat). */
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
        // Chan truong hop model tra ve ca doan dai thay vi mot cau hoi
        if (rewritten.length() > question.length() * 6 + 200) return question;
        if (!rewritten.equals(question)) {
            log.debug("Query rewrite: '{}' -> '{}'", question, rewritten);
        }
        return rewritten;
    }

    /**
     * HyDE: nho LLM viet mot doan tra loi GIA DINH roi nhung doan do. Vector cua mot
     * cau tra loi thuong gan vector cua tai lieu that hon la vector cua cau hoi.
     */
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
            log.warn("HyDE loi ({}) -> bo qua bien the nay.", e.getMessage());
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
        // Cat bot lich su: cau tra loi dai lam prompt phong to va tang chi phi
        int maxChars = props.getQueryRewrite().getMaxHistoryChars();
        if (out.length() > maxChars) {
            out = "[...]\n" + out.substring(out.length() - maxChars);
        }
        return out;
    }
}
