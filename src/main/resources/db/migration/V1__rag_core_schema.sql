-- =====================================================================
-- V1 - Schema loi cua he thong RAG.
--
-- Viet theo kieu IDEMPOTENT (IF NOT EXISTS) va chay voi baseline-version=0
-- de ap dung duoc CA HAI truong hop:
--   * DB moi hoan toan
--   * DB cu da co bang rag_chunks do RagVectorRepository.initSchema() tao
--
-- SO CHIEU VECTOR lay tu placeholder ${embeddingDim}, duoc set trong
-- application.properties tu rag.embedding.dimensions. Nho vay doi model
-- embedding (OpenAI 1536 / bge-m3 1024 / all-MiniLM 384) chi can doi cau hinh
-- roi tao lai schema, khong phai sua tay file migration.
--
-- LUU Y: vector cua CAU HOI va cua TAI LIEU buoc phai cung mot model. Doi model
-- tren DB da co du lieu => phai NAP LAI toan bo tai lieu. SchemaValidator canh
-- bao luc khoi dong neu config lech voi DB.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- ---------------------------------------------------------------------
-- Text search config cho tieng Viet: 'simple' + unaccent.
-- Nho unaccent nen "nghi phep" khop duoc voi "nghỉ phép" (go khong dau).
-- ---------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = 'vi') THEN
        CREATE TEXT SEARCH CONFIGURATION vi (COPY = simple);
        ALTER TEXT SEARCH CONFIGURATION vi
            ALTER MAPPING FOR hword, hword_part, word WITH unaccent, simple;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- rag_documents: metadata muc TAI LIEU.
-- Giu ca ban Markdown da chuyen doi de co the bam lai chunk (re-chunk)
-- ma khong phai convert lai tu file goc.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_documents (
    id              BIGSERIAL PRIMARY KEY,
    doc_key         TEXT        NOT NULL UNIQUE,
    file_name       TEXT        NOT NULL,
    title           TEXT,
    category        TEXT,
    department      TEXT,
    doc_number      TEXT,
    doc_version     TEXT,
    source_path     TEXT,
    source_format   TEXT,
    effective_date  DATE,
    expires_date    DATE,
    status          TEXT        NOT NULL DEFAULT 'ACTIVE',
    allowed_roles   TEXT[],
    content_sha256  TEXT,
    markdown        TEXT,
    chunk_count     INT         NOT NULL DEFAULT 0,
    char_count      INT         NOT NULL DEFAULT 0,
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_rag_documents_category  ON rag_documents (category);
CREATE INDEX IF NOT EXISTS idx_rag_documents_status    ON rag_documents (status);
CREATE INDEX IF NOT EXISTS idx_rag_documents_effective ON rag_documents (effective_date DESC);
CREATE INDEX IF NOT EXISTS idx_rag_documents_sha       ON rag_documents (content_sha256);
CREATE INDEX IF NOT EXISTS idx_rag_documents_name_trgm ON rag_documents USING gin (file_name gin_trgm_ops);

-- ---------------------------------------------------------------------
-- rag_chunks: child chunk + parent chunk + vector + tsvector.
-- CREATE cho DB moi; cac ALTER ben duoi nang cap DB cu.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_chunks (
    id             BIGSERIAL PRIMARY KEY,
    doc_id         TEXT NOT NULL,
    file_name      TEXT NOT NULL,
    category       TEXT,
    chunk_index    INT  NOT NULL,
    content        TEXT NOT NULL,
    context        TEXT,
    parent_content TEXT,
    embedding      vector(${embeddingDim}) NOT NULL,
    tsv            tsvector,
    created_at     TIMESTAMPTZ DEFAULT now()
);

ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS category       TEXT;
ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS document_id    BIGINT;
ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS heading_path   TEXT;
ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS content_sha256 TEXT;
ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS char_count     INT;
ALTER TABLE rag_chunks ADD COLUMN IF NOT EXISTS created_at     TIMESTAMPTZ DEFAULT now();

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_rag_chunks_document'
    ) THEN
        ALTER TABLE rag_chunks
            ADD CONSTRAINT fk_rag_chunks_document
            FOREIGN KEY (document_id) REFERENCES rag_documents (id) ON DELETE CASCADE;
    END IF;
END $$;

-- ---------------------------------------------------------------------
-- tsv duoc sinh boi TRIGGER (khong phai code Java ghi tay) de khong bao
-- gio lech voi content. Day la cach chac chan hon generated column vi
-- config 'vi' co dung dictionary unaccent.
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION rag_chunks_tsv_sync() RETURNS trigger AS $$
BEGIN
    NEW.tsv := to_tsvector('vi',
        coalesce(NEW.heading_path, '') || ' ' ||
        coalesce(NEW.context, '')      || ' ' ||
        coalesce(NEW.content, ''));
    RETURN NEW;
END $$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_rag_chunks_tsv ON rag_chunks;
CREATE TRIGGER trg_rag_chunks_tsv
    BEFORE INSERT OR UPDATE OF content, context, heading_path ON rag_chunks
    FOR EACH ROW EXECUTE FUNCTION rag_chunks_tsv_sync();

-- Dung lai tsv cho du lieu cu (truoc day index bang config 'simple')
UPDATE rag_chunks
   SET tsv = to_tsvector('vi',
        coalesce(heading_path, '') || ' ' || coalesce(context, '') || ' ' || coalesce(content, ''))
 WHERE tsv IS NULL
    OR tsv <> to_tsvector('vi',
        coalesce(heading_path, '') || ' ' || coalesce(context, '') || ' ' || coalesce(content, ''));

CREATE INDEX IF NOT EXISTS idx_rag_chunks_embedding
    ON rag_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_tsv      ON rag_chunks USING gin (tsv);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_doc      ON rag_chunks (doc_id);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_category ON rag_chunks (category);
CREATE INDEX IF NOT EXISTS idx_rag_chunks_document ON rag_chunks (document_id);

-- ---------------------------------------------------------------------
-- Hoi thoai: thay cho ConversationMemory chi nam trong RAM.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_conversations (
    id             TEXT PRIMARY KEY,
    user_id        TEXT,
    title          TEXT,
    category       TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_active_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rag_conversations_user   ON rag_conversations (user_id);
CREATE INDEX IF NOT EXISTS idx_rag_conversations_active ON rag_conversations (last_active_at DESC);

CREATE TABLE IF NOT EXISTS rag_messages (
    id                BIGSERIAL PRIMARY KEY,
    conversation_id   TEXT NOT NULL REFERENCES rag_conversations (id) ON DELETE CASCADE,
    role              TEXT NOT NULL,
    content           TEXT NOT NULL,
    rewritten_query   TEXT,
    provider          TEXT,
    model             TEXT,
    input_tokens      INT,
    output_tokens     INT,
    cost_usd          NUMERIC(12, 6),
    latency_ms        INT,
    abstained         BOOLEAN NOT NULL DEFAULT false,
    cache_hit         TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rag_messages_conv    ON rag_messages (conversation_id, id);
CREATE INDEX IF NOT EXISTS idx_rag_messages_created ON rag_messages (created_at DESC);

CREATE TABLE IF NOT EXISTS rag_message_citations (
    id          BIGSERIAL PRIMARY KEY,
    message_id  BIGINT NOT NULL REFERENCES rag_messages (id) ON DELETE CASCADE,
    chunk_id    BIGINT,
    document_id BIGINT,
    file_name   TEXT,
    heading_path TEXT,
    snippet     TEXT,
    score       DOUBLE PRECISION,
    rank        INT
);
CREATE INDEX IF NOT EXISTS idx_rag_citations_message ON rag_message_citations (message_id);

CREATE TABLE IF NOT EXISTS rag_feedback (
    id              BIGSERIAL PRIMARY KEY,
    message_id      BIGINT NOT NULL REFERENCES rag_messages (id) ON DELETE CASCADE,
    conversation_id TEXT,
    user_id         TEXT,
    rating          SMALLINT NOT NULL,
    comment         TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rag_feedback_message ON rag_feedback (message_id);
CREATE INDEX IF NOT EXISTS idx_rag_feedback_rating  ON rag_feedback (rating);

-- ---------------------------------------------------------------------
-- Cache cau tra loi: exact-match theo hash + semantic theo cosine.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_answer_cache (
    id            BIGSERIAL PRIMARY KEY,
    cache_key     TEXT NOT NULL UNIQUE,
    scope_key     TEXT NOT NULL,
    question      TEXT NOT NULL,
    answer        TEXT NOT NULL,
    citations     JSONB,
    provider      TEXT,
    model         TEXT,
    embedding     vector(${embeddingDim}),
    hits          INT NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_hit_at   TIMESTAMPTZ,
    expires_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_rag_cache_scope   ON rag_answer_cache (scope_key);
CREATE INDEX IF NOT EXISTS idx_rag_cache_expires ON rag_answer_cache (expires_at);
CREATE INDEX IF NOT EXISTS idx_rag_cache_embed
    ON rag_answer_cache USING hnsw (embedding vector_cosine_ops);

-- ---------------------------------------------------------------------
-- Job nap lieu: luu DB nen khong mat khi restart, xem lai duoc lich su.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_ingest_jobs (
    id            TEXT PRIMARY KEY,
    state         TEXT NOT NULL,
    kind          TEXT,
    category      TEXT,
    total         INT NOT NULL DEFAULT 0,
    processed     INT NOT NULL DEFAULT 0,
    succeeded     INT NOT NULL DEFAULT 0,
    failed        INT NOT NULL DEFAULT 0,
    skipped       INT NOT NULL DEFAULT 0,
    total_chunks  INT NOT NULL DEFAULT 0,
    current_file  TEXT,
    errors        JSONB,
    cancel_requested BOOLEAN NOT NULL DEFAULT false,
    created_by    TEXT,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at   TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_rag_jobs_started ON rag_ingest_jobs (started_at DESC);

-- ---------------------------------------------------------------------
-- Eval: golden dataset luu ben, chay lai duoc va so sanh giua cac lan.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_eval_cases (
    id              BIGSERIAL PRIMARY KEY,
    suite           TEXT NOT NULL DEFAULT 'default',
    question        TEXT NOT NULL,
    expected_source TEXT,
    expected_answer TEXT,
    category        TEXT,
    active          BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_cases_suite ON rag_eval_cases (suite, active);

CREATE TABLE IF NOT EXISTS rag_eval_runs (
    id                  BIGSERIAL PRIMARY KEY,
    suite               TEXT NOT NULL,
    provider            TEXT,
    model               TEXT,
    total               INT NOT NULL DEFAULT 0,
    judged              INT NOT NULL DEFAULT 0,
    skipped             INT NOT NULL DEFAULT 0,
    avg_faithfulness    DOUBLE PRECISION,
    avg_answer_relevance DOUBLE PRECISION,
    context_recall      DOUBLE PRECISION,
    abstain_rate        DOUBLE PRECISION,
    avg_latency_ms      INT,
    total_cost_usd      NUMERIC(12, 6),
    params              JSONB,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_runs_suite ON rag_eval_runs (suite, created_at DESC);

CREATE TABLE IF NOT EXISTS rag_eval_results (
    id           BIGSERIAL PRIMARY KEY,
    run_id       BIGINT NOT NULL REFERENCES rag_eval_runs (id) ON DELETE CASCADE,
    case_id      BIGINT,
    question     TEXT NOT NULL,
    answer       TEXT,
    sources      JSONB,
    faithfulness DOUBLE PRECISION,
    answer_relevance DOUBLE PRECISION,
    source_hit   BOOLEAN,
    judged       BOOLEAN NOT NULL DEFAULT true,
    abstained    BOOLEAN NOT NULL DEFAULT false,
    latency_ms   INT
);
CREATE INDEX IF NOT EXISTS idx_rag_eval_results_run ON rag_eval_results (run_id);

-- ---------------------------------------------------------------------
-- Cau hinh runtime (provider/model mac dinh, tham so retrieval...).
-- Nho bang nay, doi top-k khong con phai restart ung dung.
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS rag_settings (
    setting_key  TEXT PRIMARY KEY,
    setting_value TEXT,
    updated_by   TEXT,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
