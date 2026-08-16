-- =====================================================================
-- V2 - Nguoi dung dang nhap bang tai khoan cong ty (Microsoft Entra ID).
--
-- Bang nay la BAN SAO DE DOC phuc vu audit va giao dien quan tri, KHONG phai
-- nguon su that ve quyen. Nguon su that la Entra: role lay tu app role trong
-- token, thanh vien nhom lay tu Microsoft Graph (co cache 15 phut).
--
-- Ly do khong luu quyen o day: nhan ban du lieu "ai thuoc phong nao" vao ung dung
-- la tu nhan no dong bo - nguoi chuyen phong se giu quyen cu. Xem docs/BOT-PLATFORM.md
-- muc 4.1.
-- =====================================================================

CREATE TABLE IF NOT EXISTS rag_users (
    entra_object_id  UUID PRIMARY KEY,
    upn              TEXT NOT NULL,
    display_name     TEXT,
    department       TEXT,
    job_title        TEXT,
    -- Mang objectId cac nhom Entra, chup lai luc dang nhap gan nhat. Chi de chan doan
    -- ("luc 9h he thong thay anh A thuoc nhung nhom nay"), khong dung de phan quyen.
    group_ids        JSONB,
    groups_synced_at TIMESTAMPTZ,
    last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rag_users_upn       ON rag_users (lower(upn));
CREATE INDEX IF NOT EXISTS idx_rag_users_last_seen ON rag_users (last_seen_at DESC);
