-- =====================================================================
-- V6 - Ghi nhan BOT NAO tra loi cau nay.
--
-- Truoc do khong the tra loi duoc cau hoi van hanh co ban nhat: "bot Phap che
-- dang tu choi bao nhieu phan tram cau hoi?". V3 co them rag_conversations.bot_id
-- nhung KHONG duong ghi nao dat gia tri cho no, nen cot do luon NULL - co so ha
-- tang bao cao coi nhu khong ton tai.
--
-- Dat bot_slug o muc TIN NHAN chu khong chi o muc hoi thoai, vi hai ly do:
--   1. Bao cao chi can doc mot bang: rag_messages (+ rag_feedback qua message_id).
--      Neu de o hoi thoai thi moi cau thong ke deu phai join them mot bang.
--   2. Mot hoi thoai co the doi bot (quan tri vien doi rang buoc channel -> bot),
--      luc do quy toan bo lich su cho bot moi la sai.
--
-- Dung slug chu khong dung khoa ngoai toi rag_bots: bao cao phai con nguyen khi
-- mot bot bi xoa. Mat bot ma mat luon so lieu lich su cua no la mat dung thu can
-- de giai trinh.
-- =====================================================================

ALTER TABLE rag_messages ADD COLUMN IF NOT EXISTS bot_slug TEXT;

-- Cau bao cao luon loc theo cua so thoi gian roi gom theo bot.
CREATE INDEX IF NOT EXISTS idx_rag_messages_bot
    ON rag_messages (bot_slug, created_at DESC);

-- Cot nay co tu V3 nhung chua bao gio duoc ghi; tu ban nay
-- ConversationRepository.ensureConversation dat gia tri cho no.
COMMENT ON COLUMN rag_conversations.bot_id IS
    'Bot phuc vu hoi thoai (NULL = duong web, khong qua bot nao).';
COMMENT ON COLUMN rag_messages.bot_slug IS
    'Slug bot tra loi, giu lai ke ca khi bot bi xoa. NULL = duong web.';
