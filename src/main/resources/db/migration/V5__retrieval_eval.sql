-- =====================================================================
-- V5 - Do CHAT LUONG TRUY XUAT tach roi khoi chat luong cau tra loi.
--
-- Bo metric hien co (faithfulness, answer relevance) deu can mot lan goi LLM giam
-- khao cho MOI case. Hau qua thuc te: chay eval ton tien va cham, nen khong ai chay
-- thuong xuyen, nen moi thay doi tham so deu la doan mo.
--
-- Metric o day chi can mot lan nhung cau hoi + vai cau SQL - re den muc chay duoc
-- sau MOI lan doi tham so. Do la khac biet giua "co bo do" va "co bo do dung duoc".
--
-- Dung chung bang rag_eval_runs de so sanh duoc theo thoi gian; phan biet bang cot
-- kind.
-- =====================================================================

ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS kind TEXT NOT NULL DEFAULT 'ANSWER';

-- Ty le cau hoi ma tai lieu dung nam trong top-N ket qua truy xuat.
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS recall_at_1  DOUBLE PRECISION;
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS recall_at_3  DOUBLE PRECISION;
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS recall_at_5  DOUBLE PRECISION;
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS recall_at_10 DOUBLE PRECISION;

-- Mean Reciprocal Rank: trung binh cua 1/thu_hang cua ket qua dung dau tien.
-- Nhay hon recall@k vi phan biet duoc "dung o vi tri 1" voi "dung o vi tri 5" -
-- khac biet nay rat quan trong khi chi co top-6 di vao prompt.
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS mrr DOUBLE PRECISION;

-- Do sau khi rerank (neu lan chay co bat rerank). So sanh voi mrr cho biet bo rerank
-- dang lam TOT LEN hay LAM HONG thu tu - cau hoi khong tra loi duoc neu chi nhin
-- diem cuoi cung.
ALTER TABLE rag_eval_runs ADD COLUMN IF NOT EXISTS mrr_reranked DOUBLE PRECISION;

CREATE INDEX IF NOT EXISTS idx_rag_eval_runs_kind ON rag_eval_runs (kind, suite, created_at DESC);
