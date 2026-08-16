-- =====================================================================
-- V3 - Nen tang NHIEU BOT.
--
-- QUYET DINH THIET KE QUAN TRONG: collection.slug CHINH LA cot `category`
-- da co cua rag_documents/rag_chunks. Nghia la migration nay KHONG dong den
-- rag_chunks va KHONG doi mot dong SQL tim kiem nao trong ChunkRepository.
--
-- Vi sao khong doi `category` thanh khoa ngoai `collection_id`:
--   * Toan bo SQL tim kiem (vector + full-text + over-fetch cho pgvector) da
--     duoc chinh ky. Doi dieu kien loc o do la dat cuoc ca chat luong truy xuat
--     vao mot viec thuan tuy hanh chinh.
--   * Bat bien `doc_key = category/fileName` van giu nguyen y nghia.
--   * Doi lai chi mat mot rang buoc toan ven o tang DB; bu bang UNIQUE tren slug
--     va bang viec MOI duong ghi category deu di qua CollectionService.
--
-- Cac bang o day mo ta CHINH SACH (bot nao doc tap nao, ai duoc dung bot nao),
-- con "ai thuoc phong nao" van la cua Entra. Xem docs/BOT-PLATFORM.md muc 4.1.
-- =====================================================================

-- ---------------------------------------------------------------------
-- Collection: mot tap tai lieu. slug = category.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_collections (
    id              BIGSERIAL PRIMARY KEY,
    slug            TEXT        NOT NULL UNIQUE,
    name            TEXT        NOT NULL,
    description     TEXT,
    -- MAC DINH FALSE. Cau tra loi trong channel hien ra cho MOI thanh vien channel,
    -- nen mot tap tai lieu chi duoc tra loi cong khai khi co nguoi CHU DONG bat.
    -- Mac dinh mo la kieu mac dinh sai o cho ton kem nhat.
    channel_allowed BOOLEAN     NOT NULL DEFAULT false,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ai DOC duoc collection: theo nhom Entra (nguon su that do IT/HR duy tri).
CREATE TABLE IF NOT EXISTS rag_collection_acl (
    collection_id  BIGINT NOT NULL REFERENCES rag_collections (id) ON DELETE CASCADE,
    entra_group_id UUID   NOT NULL,
    -- Chi de hien thi. KHONG bao gio dung de phan quyen: ten nhom doi duoc, id thi khong.
    group_name     TEXT,
    granted_by     TEXT,
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (collection_id, entra_group_id)
);
CREATE INDEX IF NOT EXISTS idx_rag_collection_acl_group ON rag_collection_acl (entra_group_id);

-- ---------------------------------------------------------------------
-- Bot: persona + model + tap tai lieu duoc doc.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_bots (
    id             BIGSERIAL PRIMARY KEY,
    slug           TEXT        NOT NULL UNIQUE,
    display_name   TEXT        NOT NULL,
    description    TEXT,
    -- Cach 2 (moi bot mot Azure Bot rieng): dinh tuyen theo activity.recipient.id.
    -- NULL = dung chung mot Azure Bot, dinh tuyen bang rag_bot_channels.
    teams_app_id   TEXT,
    -- Bot dung cho chat rieng va cho moi noi khong khop luat dinh tuyen nao.
    is_default     BOOLEAN     NOT NULL DEFAULT false,
    persona_prompt TEXT,
    greeting       TEXT,
    llm_provider   TEXT,
    llm_model      TEXT,
    status         TEXT        NOT NULL DEFAULT 'ACTIVE',
    created_by     TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Chi duoc mot bot mac dinh. Rang buoc o DB chu khong o code: hai bot mac dinh thi
-- dinh tuyen tro thanh khong xac dinh, va loi do rat kho tim.
CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_bots_one_default
    ON rag_bots (is_default) WHERE is_default;

CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_bots_teams_app
    ON rag_bots (teams_app_id) WHERE teams_app_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS rag_bot_collections (
    bot_id        BIGINT NOT NULL REFERENCES rag_bots (id) ON DELETE CASCADE,
    collection_id BIGINT NOT NULL REFERENCES rag_collections (id) ON DELETE CASCADE,
    PRIMARY KEY (bot_id, collection_id)
);

-- Ai duoc DUNG bot. Khac han "ai duoc DOC collection": mot nguoi co the duoc dung bot
-- Nhan su ma van khong doc duoc tai lieu mat cua Nhan su. Pham vi cuoi cung la GIAO
-- cua hai thu do.
CREATE TABLE IF NOT EXISTS rag_bot_audience (
    bot_id         BIGINT NOT NULL REFERENCES rag_bots (id) ON DELETE CASCADE,
    principal_type TEXT   NOT NULL CHECK (principal_type IN ('GROUP', 'USER')),
    principal_id   UUID   NOT NULL,
    display_name   TEXT,
    PRIMARY KEY (bot_id, principal_type, principal_id)
);
CREATE INDEX IF NOT EXISTS idx_rag_bot_audience_principal ON rag_bot_audience (principal_id);

-- Cach 1 (mot Azure Bot, nhieu bot logic): Team/channel nao dung bot nao.
CREATE TABLE IF NOT EXISTS rag_bot_channels (
    id                BIGSERIAL PRIMARY KEY,
    bot_id            BIGINT NOT NULL REFERENCES rag_bots (id) ON DELETE CASCADE,
    team_aad_group_id UUID   NOT NULL,
    -- NULL = ap dung cho moi channel cua team. Co gia tri = chi channel do.
    channel_id        TEXT,
    created_by        TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_rag_bot_channels_team
    ON rag_bot_channels (team_aad_group_id, COALESCE(channel_id, ''));

-- Quyen QUAN TRI muc min: ai so huu bot nao, ai sua duoc collection nao.
-- Co y giu trong ung dung chu khong nho Entra: moi bot moi lai mo ticket xin nhom
-- Entra thi se chet o khau quy trinh.
CREATE TABLE IF NOT EXISTS rag_grants (
    id             BIGSERIAL PRIMARY KEY,
    principal_type TEXT   NOT NULL CHECK (principal_type IN ('GROUP', 'USER')),
    principal_id   UUID   NOT NULL,
    scope_type     TEXT   NOT NULL CHECK (scope_type IN ('BOT', 'COLLECTION')),
    scope_id       BIGINT NOT NULL,
    role           TEXT   NOT NULL CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    display_name   TEXT,
    granted_by     TEXT,
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (principal_type, principal_id, scope_type, scope_id, role)
);
CREATE INDEX IF NOT EXISTS idx_rag_grants_principal ON rag_grants (principal_id);
CREATE INDEX IF NOT EXISTS idx_rag_grants_scope     ON rag_grants (scope_type, scope_id);

-- ---------------------------------------------------------------------
-- Truy vet: cau hoi thuoc bot nao, cua ai. Cho bao cao chi phi/chat luong theo bot
-- va cho audit "ai da doc gi".
-- ---------------------------------------------------------------------
ALTER TABLE rag_conversations ADD COLUMN IF NOT EXISTS bot_id          BIGINT REFERENCES rag_bots (id);
ALTER TABLE rag_conversations ADD COLUMN IF NOT EXISTS entra_object_id UUID;
CREATE INDEX IF NOT EXISTS idx_rag_conversations_bot ON rag_conversations (bot_id);

-- ---------------------------------------------------------------------
-- Nap du lieu ban dau tu category dang co.
--
-- channel_allowed = false cho TAT CA: khong doan thay quan tri vien tap nao duoc
-- phep tra loi cong khai. He qua la ngay sau khi nang cap, bot tu choi tra loi
-- trong channel cho den khi co nguoi bat tuong minh - dung y do.
-- ---------------------------------------------------------------------
INSERT INTO rag_collections (slug, name, created_by)
SELECT DISTINCT d.category, d.category, 'migration-v3'
  FROM rag_documents d
 WHERE d.category IS NOT NULL AND d.category <> ''
ON CONFLICT (slug) DO NOTHING;

-- Bot mac dinh: doc tat ca collection dang co, khong gioi han doi tuong dung.
-- Giu nguyen hanh vi hien tai cua he thong sau khi nang cap.
INSERT INTO rag_bots (slug, display_name, description, is_default, created_by)
SELECT 'chung', 'Trợ lý tài liệu', 'Bot mặc định cho toàn công ty', true, 'migration-v3'
 WHERE NOT EXISTS (SELECT 1 FROM rag_bots);

INSERT INTO rag_bot_collections (bot_id, collection_id)
SELECT b.id, c.id
  FROM rag_bots b
 CROSS JOIN rag_collections c
 WHERE b.slug = 'chung'
ON CONFLICT DO NOTHING;
