-- =====================================================================
-- V7 - Nhat ky thao tac quan tri (audit trail).
--
-- Truoc ban nay he thong ghi duoc "ai da dang nhap" (rag_users) nhung KHONG ghi
-- "ai da lam gi": xoa tai lieu nao, doi ACL cua collection nao, doi tham so
-- runtime nao. Rieng POST /api/v1/rag/settings ap dung ngay khong can restart,
-- tuc mot nguoi co quyen ADMIN co the doi hanh vi tra loi cua ca he thong ma
-- khong de lai vet gi. Voi mot cong ty chung khoan day la thu kiem toan noi bo
-- hoi dau tien.
--
-- Ghi bang FILTER chu khong bang loi goi rai rac trong tung controller: mot
-- endpoint moi them vao sau nay se duoc ghi nhat ky TU DONG. Cach cu (moi
-- controller tu goi audit) chac chan se co cho bi quen, va cho bi quen lai
-- chinh la cho can nhat.
--
-- KHONG dung khoa ngoai toi rag_users: nhat ky phai con nguyen ca khi tai khoan
-- bi xoa khoi thu muc - cung ly do voi rag_messages.bot_slug o V6.
-- =====================================================================

CREATE TABLE IF NOT EXISTS rag_audit_log (
    id            BIGSERIAL PRIMARY KEY,
    -- Dinh danh ben vung cua nguoi thuc hien (objectId Entra, hoac id cua API key).
    actor_id      TEXT,
    -- Email/UPN tai thoi diem thao tac. Luu ban sao CO Y: doi mail sau nay khong
    -- duoc lam sai lech nhat ky cu.
    actor_upn     TEXT,
    actor_roles   TEXT,
    -- api-key | entra | bot | anonymous
    actor_source  TEXT,

    -- Hanh dong dang <METHOD> <duong dan da chuan hoa>, vi du "DELETE /admin/documents/{id}".
    action        TEXT        NOT NULL,
    method        TEXT        NOT NULL,
    path          TEXT        NOT NULL,
    query_string  TEXT,
    -- Tom tat than request DA CHE cac truong nhay cam (xem AuditFilter.redact).
    payload       TEXT,

    status        INT         NOT NULL,
    -- true khi status < 400. Tach cot rieng de cau "cac thao tac bi tu choi"
    -- khong phai tinh toan tren moi dong.
    succeeded     BOOLEAN     NOT NULL,
    latency_ms    INT,
    client_ip     TEXT,
    user_agent    TEXT,
    trace_id      TEXT,

    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Truy van thuong gap nhat: "30 ngay gan day, moi nhat truoc".
CREATE INDEX IF NOT EXISTS idx_rag_audit_created
    ON rag_audit_log (created_at DESC);

-- "Nguoi X da lam gi" - cau hoi thu hai cua kiem toan.
CREATE INDEX IF NOT EXISTS idx_rag_audit_actor
    ON rag_audit_log (actor_upn, created_at DESC);

-- "Ai da dong vao tai lieu / ai da doi cau hinh".
CREATE INDEX IF NOT EXISTS idx_rag_audit_action
    ON rag_audit_log (action, created_at DESC);

-- Chi muc mot phan: cac thao tac bi tu choi (401/403) thuong it nhung lai la
-- thu can tim nhanh nhat khi co su co bao mat.
CREATE INDEX IF NOT EXISTS idx_rag_audit_denied
    ON rag_audit_log (created_at DESC) WHERE NOT succeeded;

COMMENT ON TABLE rag_audit_log IS
    'Nhat ky thao tac thay doi du lieu/cau hinh. Ghi tu dong boi AuditFilter.';
COMMENT ON COLUMN rag_audit_log.payload IS
    'Than request da cat ngan va da che secret/api-key/password.';
