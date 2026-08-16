package com.ai.aiagent.chat;

/**
 * Phan cau hinh RIENG cua mot bot anh huong den cach tra loi: giong dieu va model.
 *
 * Nam o package {@code chat} chu khong phai {@code bot} de giu chieu phu thuoc mot chieu:
 * {@code bot} -> {@code chat}. Neu {@code RagChatService} phai import tu {@code bot} thi
 * lop hoi-dap lai phu thuoc nguoc vao lop van chuyen Teams, va giao dien web se keo theo
 * ca the gioi cua Teams.
 *
 * @param id            khoa cua {@code rag_bots}, de gan hoi thoai cho bot. Null o duong web
 * @param slug          dinh danh bot, de ghi vao log/hoi thoai va bao cao. Slug chu khong
 *                      phai id duoc ghi vao {@code rag_messages}: so lieu lich su phai con
 *                      doc duoc ca khi bot da bi xoa
 * @param personaPrompt vai tro/giong dieu rieng, chen vao dau system prompt. KHONG the
 *                      ghi de cac quy tac bat buoc - xem {@link PromptBuilder#systemPrompt}
 * @param provider      de null thi dung mac dinh he thong
 * @param model         de null thi dung mac dinh he thong
 */
public record BotProfile(Long id, String slug, String personaPrompt, String provider,
                         String model) {

    private static final BotProfile NONE = new BotProfile(null, null, null, null, null);

    /** Duong web va cac loi goi noi bo: khong co bot nao, dung mac dinh he thong. */
    public static BotProfile none() {
        return NONE;
    }

    /**
     * Nhan dung trong metric va bao cao. KHONG bao gio tra null: mot the {@code bot} rong
     * trong Prometheus lam vo ca chuoi so lieu, va "web" la mot gia tri co nghia that -
     * cau hoi den tu giao dien web chu khong phai tu bot nao.
     */
    public String label() {
        return slug == null || slug.isBlank() ? "web" : slug;
    }

    public boolean hasPersona() {
        return personaPrompt != null && !personaPrompt.isBlank();
    }
}
