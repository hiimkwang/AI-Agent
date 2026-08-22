-- =====================================================================
-- V8 - Uy quyen theo NAMESPACE: cap cho mot nguoi (hoac mot nhom) quyen tu tao
-- nhom tai lieu duoi mot tien to slug.
--
-- Vi sao can bang rieng thay vi them mot 'scope_type' vao rag_grants:
--   rag_grants co UNIQUE (principal_type, principal_id, scope_type, scope_id,
--   role) va scope_id la BIGINT NOT NULL. Mot uy quyen theo tien to khong co
--   scope_id, nen se phai dat scope_id = 0 va noi rong UNIQUE de chua scope_ref
--   - tuc phai DROP mot rang buoc dang bao ve du lieu that. Them bang moi khong
--   cham vao rang buoc do.
--
-- Vi sao la TIEN TO chu khong phai "duoc tao tuy y":
--   rag_collections.slug CHINH LA cot category cua rag_chunks, tuc mot namespace
--   dung chung cho ca he thong tim kiem. De ai cung tao slug tuy y thi hai phong
--   se dat trung ten (quy-trinh, bieu-mau) va khong sua lai duoc nua vi tai lieu
--   da nap theo category do. Tien to bien namespace dung chung thanh nhieu vung
--   rieng, va van chi la mot dong cau hinh.
--
-- max_collections la chan that, khong phai trang tri: khong co no thi mot nguoi
-- co the tao vai nghin nhom tai lieu va lam bang chon category vo dung.
-- =====================================================================

CREATE TABLE IF NOT EXISTS rag_namespace_grants (
    id              BIGSERIAL PRIMARY KEY,
    principal_type  TEXT   NOT NULL CHECK (principal_type IN ('GROUP', 'USER')),
    principal_id    UUID   NOT NULL,
    slug_prefix     TEXT   NOT NULL CHECK (slug_prefix ~ '^[a-z0-9][a-z0-9._-]*$'),
    max_collections INT    NOT NULL DEFAULT 20 CHECK (max_collections > 0),
    display_name    TEXT,
    granted_by      TEXT,
    granted_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (principal_type, principal_id, slug_prefix)
);

CREATE INDEX IF NOT EXISTS idx_rag_namespace_grants_principal
    ON rag_namespace_grants (principal_id);
