-- =====================================================================
-- V4 - Chat luong tim kiem: trong so truong cho tsvector + tu dien thuat ngu.
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1) TRONG SO THEO TRUONG cho tsvector.
--
-- Truoc day trigger noi PHANG heading_path + context + content, moi tu cung
-- trong so. Hau qua: mot chunk chi tinh co nhac "nghi phep" giua than bai duoc
-- xep ngang voi chunk co HEADING dung la "Che do nghi phep". Heading la tin hieu
-- manh nhat ve chu de cua chunk ma lai bi bo qua.
--
-- Trong so mac dinh cua ts_rank_cd la {D=0.1, C=0.2, B=0.4, A=1.0}, nen:
--   A = heading_path -> 1.0   (chu de, tin hieu manh nhat)
--   B = content      -> 0.4   (noi dung that)
--   C = context      -> 0.2   (cau ngu canh do LLM sinh, chi la mo ta)
--
-- CO Y khong dat content o C: dat content thap hon nua thi heading manh gap 5 lan
-- noi dung, va cac chunk co heading chung chung se chiem het top.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION rag_chunks_tsv_sync() RETURNS trigger AS $$
BEGIN
    NEW.tsv :=
        setweight(to_tsvector('vi', coalesce(NEW.heading_path, '')), 'A') ||
        setweight(to_tsvector('vi', coalesce(NEW.content, '')),      'B') ||
        setweight(to_tsvector('vi', coalesce(NEW.context, '')),      'C');
    RETURN NEW;
END $$ LANGUAGE plpgsql;

-- Nap lai tsv cho du lieu cu. KHONG can nap lai tai lieu (khong dung den embedding),
-- nhung day la mot luot UPDATE toan bang - voi kho lon se mat vai phut.
UPDATE rag_chunks
   SET tsv = setweight(to_tsvector('vi', coalesce(heading_path, '')), 'A') ||
             setweight(to_tsvector('vi', coalesce(content, '')),      'B') ||
             setweight(to_tsvector('vi', coalesce(context, '')),      'C');

-- ---------------------------------------------------------------------
-- 2) TU DIEN THUAT NGU / VIET TAT.
--
-- Bat buoc voi nganh chung khoan: can bo go "UBCK", "VSD", "CTCK", "T+2", con
-- tai lieu viet day du. Vector search KHONG tu noi duoc cac cap nay mot cach
-- dang tin, va full-text thi cang khong - hai chuoi khong chung mot tu nao.
--
-- Dung o hai cho: mo rong tsquery cua nhanh full-text, va chen vao prompt viet lai
-- cau hoi de cau viet lai dung dung thuat ngu cua tai lieu.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_synonyms (
    id              BIGSERIAL PRIMARY KEY,
    term            TEXT   NOT NULL,
    expansions      TEXT[] NOT NULL,
    -- NULL = ap dung toan he thong. Co gia tri = chi trong mot nhom tai lieu
    -- (vd "margin" trong tai lieu Moi gioi khac trong tai lieu Ke toan).
    collection_slug TEXT,
    note            TEXT,
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- NULLS NOT DISTINCT (Postgres 15+): neu khong co, hai dong cung term voi
    -- collection_slug = NULL deu duoc coi la khac nhau va lot qua rang buoc.
    CONSTRAINT uq_rag_synonyms UNIQUE NULLS NOT DISTINCT (term, collection_slug)
);
CREATE INDEX IF NOT EXISTS idx_rag_synonyms_term ON rag_synonyms (lower(term));

-- Bo tu vung khoi dau cho cong ty chung khoan. Quan tri vien bo sung tiep tren
-- giao dien; day chi la de he thong khong bat dau tu con so khong.
INSERT INTO rag_synonyms (term, expansions, note, created_by) VALUES
    ('UBCKNN', ARRAY['Ủy ban Chứng khoán Nhà nước', 'UBCK'], 'Cơ quan quản lý', 'migration-v4'),
    ('UBCK',   ARRAY['Ủy ban Chứng khoán Nhà nước', 'UBCKNN'], NULL, 'migration-v4'),
    ('VSD',    ARRAY['Trung tâm Lưu ký Chứng khoán', 'Tổng công ty Lưu ký và Bù trừ chứng khoán Việt Nam', 'VSDC'], NULL, 'migration-v4'),
    ('CTCK',   ARRAY['công ty chứng khoán'], NULL, 'migration-v4'),
    ('NĐT',    ARRAY['nhà đầu tư'], NULL, 'migration-v4'),
    ('TKGD',   ARRAY['tài khoản giao dịch'], NULL, 'migration-v4'),
    ('KYQ',    ARRAY['ký quỹ'], NULL, 'migration-v4'),
    ('margin', ARRAY['giao dịch ký quỹ', 'ký quỹ'], NULL, 'migration-v4'),
    ('BCTC',   ARRAY['báo cáo tài chính'], NULL, 'migration-v4'),
    ('HĐLĐ',   ARRAY['hợp đồng lao động'], NULL, 'migration-v4'),
    ('BHXH',   ARRAY['bảo hiểm xã hội'], NULL, 'migration-v4'),
    ('BHYT',   ARRAY['bảo hiểm y tế'], NULL, 'migration-v4'),
    ('CBNV',   ARRAY['cán bộ nhân viên', 'người lao động'], NULL, 'migration-v4'),
    ('KPI',    ARRAY['chỉ tiêu đánh giá', 'chỉ số đánh giá hiệu quả'], NULL, 'migration-v4')
ON CONFLICT ON CONSTRAINT uq_rag_synonyms DO NOTHING;
