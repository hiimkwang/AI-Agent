# Kiến trúc AI-Agent RAG — phiên bản 0.2.0

Tài liệu mô tả hệ thống **sau khi nâng cấp**, và đối chiếu từng vấn đề đã nêu trong
bản đánh giá trước với cách xử lý cụ thể.

- Bản trước: `0.0.1` (xem git history, tài liệu đánh giá cũ ở commit trước)
- Bản này: `0.2.0`

---

## 1. Tổng quan

```
                            ┌──────────────────────────────────────────┐
  Trình duyệt  ────────────▶│ /            index.html   (hỏi–đáp)      │
                            │ /admin.html  admin.html   (quản trị)     │
                            └──────────────────┬───────────────────────┘
                                               │ X-API-Key
      ┌────────────────────────────────────────▼────────────────────────────────┐
      │ Spring Security: ApiKeyAuthFilter → RateLimitFilter → phân quyền URL    │
      └────────────────────────────────────────┬────────────────────────────────┘
                                               │
   ┌───────────────────────────────────────────┼───────────────────────────────┐
   ▼                                           ▼                               ▼
┌─────────────────┐              ┌──────────────────────────┐      ┌────────────────────┐
│ NẠP TÀI LIỆU    │              │ HỎI–ĐÁP                  │      │ ĐÁNH GIÁ / QUẢN TRỊ│
│                 │              │                          │      │                    │
│ file bất kỳ     │              │ [0] cache exact+semantic │      │ EvalService        │
│   ↓ convert     │              │ [1] lịch sử (DB)         │      │ RagSettingsService │
│ MARKDOWN        │              │ [2] biến thể truy vấn    │      │ RagMetrics         │
│   ↓ chunk theo  │              │ [3] hybrid song song+RRF │      │ RetrievalDebug     │
│   heading       │              │ [4] rerank có điểm       │      └────────────────────┘
│   ↓ embed lô    │              │ [5] CỔNG TỪ CHỐI         │
│   ↓ ghi đè      │              │ [6] prompt chống injection│
└────────┬────────┘              │ [7] sinh (stream/đồng bộ)│
         │                       │ [8] lưu + trích dẫn      │
         │                       └───────────┬──────────────┘
         ▼                                   ▼
┌──────────────────────────────────────────────────────────────────────────────┐
│ PostgreSQL 17 + pgvector — Flyway quản lý schema                             │
│ rag_documents · rag_chunks · rag_conversations · rag_messages ·              │
│ rag_message_citations · rag_feedback · rag_answer_cache · rag_ingest_jobs ·  │
│ rag_eval_{cases,runs,results} · rag_settings                                 │
└──────────────────────────────────────────────────────────────────────────────┘
```

Model: **Claude / OpenAI / Gemini / Ollama** cho chat (đều hỗ trợ stream);
**OpenAI / Ollama / LOCAL-ONNX** cho embedding; **LLM / Cohere / NONE** cho rerank.

---

## 2. Luồng nạp tài liệu

Mọi định dạng đi qua **một** định dạng trung gian duy nhất: Markdown.

```
file (.md .html .pdf .docx .xlsx .pptx .doc .xls .ppt .txt .csv)
  │
  ├─ MARKDOWN → chuẩn hoá
  ├─ HTML     → jsoup bỏ nav/footer/script/quảng cáo, chọn vùng nội dung chính
  │             → flexmark (ATX heading, giữ bảng)
  ├─ PDF      → PDFBox + suy ra heading từ 2 tín hiệu độc lập:
  │             (a) cỡ chữ so với median thân bài
  │             (b) mẫu số thứ tự "PHẦN/Chương/Mục/Điều/1.2.3"
  │             + tự loại header/footer lặp lại ≥60% số trang
  ├─ OFFICE   → POI, giữ THỨ TỰ đoạn/bảng, style "Heading N" → #,
  │             bảng → bảng Markdown, sheet Excel → heading + bảng
  └─ TXT/CSV  → CSV thành bảng Markdown
  ▼
front-matter (title, category, doc_number, version, effective_date, roles…)
  ▼
sha256 → BỎ QUA nếu nội dung không đổi
  ▼
MarkdownChunker:  block → section (theo heading) → parent ≤2400 → child ≤600 (overlap 120)
                  bảng & code fence là NGUYÊN KHỐI; bảng lớn cắt theo hàng, LẶP header
  ▼
(tuỳ chọn) sinh câu ngữ cảnh cho từng chunk
  ▼
embed theo lô 96 + retry backoff
  ▼
transaction: upsert rag_documents → xoá chunk cũ → insert chunk mới
```

Mỗi chunk mang **đường dẫn heading** (`Nội quy > Chương II — Chế độ nghỉ > Điều 3.
Nghỉ phép hằng năm`), được ghép vào text lúc nhúng và lúc index full-text, nên chunk
không còn "mất gốc".

---

## 3. Luồng hỏi–đáp

`POST /api/v1/rag/chat` (đồng bộ) hoặc `POST /api/v1/rag/chat/stream` (SSE).

| Bước | Việc | Ghi chú |
|---|---|---|
| 0 | Cache exact (hash) → semantic (cosine ≥ 0.97) | key gồm **phạm vi truy cập** + provider/model |
| 1 | Lịch sử hội thoại từ DB | không còn nằm trong RAM |
| 2 | Sinh biến thể: câu gốc **+** câu viết lại (+ HyDE) | giữ nguyên từ khoá người dùng gõ |
| 3 | Vector + full-text **song song**, gộp RRF, boost tài liệu mới | hai nhánh thất bại độc lập |
| 4 | Rerank có **điểm số** (LLM / Cohere / NONE) | phân biệt "không liên quan" vs "lỗi" |
| 5 | **Cổng từ chối trả lời** | bước hoàn toàn mới |
| 6 | Prompt có ranh giới `<tai_lieu>` + trung hoà thẻ + nhắc lại chỉ thị | chống prompt injection |
| 7 | Sinh câu trả lời, stream từng token | có báo tiến độ từng bước |
| 8 | Lưu tin nhắn + trích dẫn chi tiết + token/chi phí/latency | nền cho feedback & eval |

SSE gửi 5 loại sự kiện: `status` (tiến độ) → `citations` (**trước** khi sinh chữ) →
`token` → `done` → `error`.

Cổng từ chối quyết định theo ba đường, độ tin cậy giảm dần:

1. không có ứng viên → từ chối
2. rerank **đáng tin**: so điểm rerank với `min-rerank-score`
3. rerank **bị lỗi**: so cosine tốt nhất với `min-vector-score`

---

## 4. Đã xử lý những gì

### Nhóm chặn (bảo mật)

| # | Vấn đề cũ | Cách xử lý |
|---|---|---|
| B1 | Không có xác thực trên bất kỳ endpoint nào | Spring Security + API key `X-API-Key`, so sánh constant-time, role `ADMIN`/`USER`, mặc định **deny** |
| B2 | `ingest-folder` nhận đường dẫn tuỳ ý | `PathAllowlist`: chuẩn hoá + resolve symlink, bắt buộc nằm trong `rag.ingestion.allowed-roots`; **đã kiểm chứng chặn `C:/Windows/System32` → 403** |
| B3 | Teams webhook không xác thực | `TeamsSignatureVerifier` HMAC-SHA256 trên body thô, so sánh constant-time; endpoint tắt mặc định |
| B4 | Không chống prompt injection | `PromptBuilder`: ranh giới `<tai_lieu>` có số, **trung hoà thẻ** trong nội dung, chỉ thị nhắc lại sau phần tài liệu |
| B5 | `category` do client tự khai = không có ACL | `AccessScope.narrowTo()` — category chỉ **thu hẹp** trong phạm vi API key được phép; `rag_documents.allowed_roles` lọc ở tầng SQL |
| B6 | Mật khẩu DB plaintext, `logs/` commit vào git | `DB_PASSWORD` qua biến môi trường; `.gitignore` thêm `logs/`, `*.log`, `.env`, `*.pem` |
| — | (mới) chưa có rate limit | `RateLimitFilter` token-bucket theo client, riêng cho chat/admin/webhook |

### Nhóm lỗi code

| # | Vấn đề cũ | Cách xử lý |
|---|---|---|
| L1 | Reranker trả `[]` bị coi là lỗi → nhồi ngữ cảnh rác | `RerankResult.reliable` vs `degraded`; rỗng+đáng tin ⇒ **từ chối trả lời** |
| L2 | Nối chuỗi JSON tay cho Teams | Jackson `ObjectMapper` |
| L3 | `embedAll` một lần cho cả file | chia lô `embed-batch-size`, retry backoff luỹ tiến |
| L4 | `.txt` khai là hỗ trợ nhưng parser POI không đọc được; không có PDF | `DocumentFormat` + registry chuyển đổi riêng cho từng loại, **có PDF** |
| L5 | `plainto_tsquery('simple')` ghép AND ⇒ nhánh full-text vô dụng | config `'vi'` (+`unaccent`), `TsQueryBuilder` ghép **OR**, xếp hạng `ts_rank_cd`; **đã kiểm chứng gõ không dấu tìm ra text có dấu** |
| L6 | Hai nhánh retrieval chạy tuần tự | `CompletableFuture` song song thật, **và thất bại độc lập** |
| L7 | `ConversationMemory` rò rỉ bộ nhớ | chuyển sang Postgres + API dọn hội thoại cũ |
| L8 | Lọc category làm hụt kết quả vector | over-fetch ×4 (cap 400) rồi cắt lại ở Java |
| L9 | Eval chấm 0 khi giám khảo parse lỗi | tách `judged`/`skipped`, case lỗi **bị loại khỏi mẫu** |
| L10 | Trả `e.getMessage()` ra client | `ApiExceptionHandler` sinh `traceId`, chi tiết chỉ vào log |
| L11 | `docId = fileName` ⇒ ghi đè âm thầm | `doc_key = category/fileName` |
| L12 | `temperature=0.2`, batch size = cả file | `temperature=0.0`, batch cố định 500 |
| L14 | Embedding buộc qua OpenAI = single point of failure | 3 provider: OpenAI / Ollama / LOCAL ONNX (chạy offline) |

### Nhóm thiếu-để-là-chatbot

| # | Vấn đề cũ | Cách xử lý |
|---|---|---|
| C1 | Không có UI | `/` màn hỏi–đáp, `/admin.html` màn quản trị 7 tab |
| C2 | Không streaming | SSE + báo tiến độ từng bước + gửi nguồn trước khi sinh chữ |
| C3 | Teams không có multi-turn | dùng `conversation.id` của Teams làm `conversationId` |
| C4 | Trích dẫn chỉ có tên file | `chunkId`, `documentId`, đường dẫn heading, **đoạn trích**, điểm, thứ hạng |
| C5 | Không có vòng phản hồi | 👍/👎 + comment → `rag_feedback`, trang quản trị liệt kê câu trả lời bị đánh giá xấu |
| C6 | Không có cache | 2 tầng, có TTL, tự dọn, key gắn phạm vi truy cập |
| C7 | Không đo lường | Micrometer + `/actuator/prometheus` + trang tổng quan: token, chi phí, latency từng bước, tỷ lệ từ chối, cache hit |
| C8 | Không có golden dataset | `rag_eval_cases/runs/results`, mỗi lần chạy lưu kèm tham số retrieval; cache tự tắt khi eval |
| C9 | Không rate limit | đã có |

### Nhóm chất lượng & vận hành

| # | Vấn đề cũ | Cách xử lý |
|---|---|---|
| Q1 | Không có ngưỡng từ chối | `RelevanceGate`, RRF **giữ lại** điểm cosine gốc |
| Q2 | Query rewrite thay thế câu gốc | multi-query: truy xuất cả hai rồi RRF |
| Q3 | Bảng bị làm phẳng | bảng → bảng Markdown ở cả POI, HTML, CSV |
| Q4 | Contextual retrieval tắt, không đo được | vẫn tắt mặc định nhưng bật/tắt được lúc runtime + đo bằng eval |
| Q5 | LLM rerank không có score | LLM rerank trả `{i, score}`; Cohere; NONE |
| Q6 | Metadata quá mỏng | `rag_documents`: ngày hiệu lực/hết hiệu lực, phòng ban, số hiệu, phiên bản, trạng thái, ACL |
| Q7 | Không dedup | dedup chunk trong tài liệu + `content_sha256` + bỏ qua file không đổi |
| Q8 | Chunk cắt theo ký tự | cắt theo heading/section, giữ nguyên bảng và code fence |
| V1 | Job trong RAM, không cancel | `rag_ingest_jobs` + `cancel_requested` + đánh dấu job bị ngắt khi restart |
| V2 | Không tune được lúc runtime | `RagSettingsService` + `POST /settings`, lưu vào DB, **áp dụng ngay** |
| V3 | Không có Flyway | có, số chiều vector truyền qua placeholder `${embeddingDim}` |
| V4 | Không validate số chiều | `SchemaValidator` chạy lúc khởi động |
| V5 | Code chết | đã xoá `BotController`, `AIConfig`, `RagDocumentSegment`, `RagVectorStoreRepository` |
| V6 | Không tune pool | cấu hình Hikari |

### Chưa làm (cố ý, cần bạn quyết)

| Việc | Lý do hoãn |
|---|---|
| OCR cho PDF scan | cần chọn engine (Tesseract/dịch vụ cloud) và cân nhắc dữ liệu ra ngoài |
| Redis cho cache/session | chỉ cần khi scale nhiều instance; hiện Postgres đủ |
| SSO / OIDC thay API key | phụ thuộc hạ tầng định danh của BSC |
| Eval trong CI | cần quyết định chạy ở đâu và ngân sách LLM cho mỗi PR |
| Test tự động | `maven.test.skip` đã bỏ nhưng chưa viết test — xem mục dưới |

---

## 5. Mô hình dữ liệu (rút gọn)

| Bảng | Vai trò |
|---|---|
| `rag_documents` | metadata tài liệu + **bản Markdown đã chuyển đổi** (để re-chunk không cần convert lại) |
| `rag_chunks` | child + parent + `heading_path` + `embedding` + `tsv` (do trigger sinh) |
| `rag_conversations` / `rag_messages` | hội thoại, token, chi phí, latency, cờ `abstained`, `cache_hit` |
| `rag_message_citations` | trích dẫn chi tiết của từng câu trả lời |
| `rag_feedback` | 👍/👎 + comment |
| `rag_answer_cache` | cache exact + semantic, có `scope_key` và TTL |
| `rag_ingest_jobs` | tiến độ job, lỗi, cờ yêu cầu dừng |
| `rag_eval_cases/runs/results` | golden dataset + lịch sử điểm kèm tham số |
| `rag_settings` | cấu hình đổi lúc runtime |

---

## 6. Bản đồ API

| Method | Path | Quyền |
|---|---|---|
| POST | `/api/v1/rag/chat` | USER |
| POST | `/api/v1/rag/chat/stream` | USER (SSE) |
| GET/DELETE | `/api/v1/rag/conversations[/{id}]` | USER |
| POST | `/api/v1/rag/feedback` | USER |
| GET | `/api/v1/rag/models` | USER |
| GET | `/api/v1/rag/settings` | USER |
| POST/PUT/DELETE | `/api/v1/rag/settings` | ADMIN |
| POST | `/api/v1/rag/teams-webhook` | HMAC |
| POST | `/api/v1/rag/admin/convert` | ADMIN — **xem trước Markdown + chunk, không nạp** |
| POST | `/api/v1/rag/admin/upload` · `upload-batch` · `ingest-folder` | ADMIN |
| GET/POST | `/api/v1/rag/admin/jobs[/{id}[/cancel]]` | ADMIN |
| GET/DELETE | `/api/v1/rag/admin/documents[/{id}[/markdown]]` | ADMIN |
| GET | `/api/v1/rag/admin/overview` · `metrics` · `stats` · `categories` | ADMIN |
| GET/DELETE | `/api/v1/rag/admin/cache` | ADMIN |
| POST | `/api/v1/rag/admin/retrieval-test` | ADMIN — **giải thích vì sao trả lời như vậy** |
| GET | `/api/v1/rag/admin/feedback/negative` | ADMIN |
| POST/GET/DELETE | `/api/v1/rag/eval/{run,cases,runs}` | ADMIN |
| GET | `/actuator/health` · `info` | công khai |
| GET | `/actuator/prometheus` · `metrics` | ADMIN |

---

## 7. Đã kiểm chứng thực tế

Chạy với Postgres 17 + pgvector trong Docker, tài liệu thật tiếng Việt
(`.md` có front-matter, `.html` có nav/footer/bảng, `.pdf` 3 trang có header/footer lặp):

| Hạng mục | Kết quả |
|---|---|
| Flyway migration trên DB rỗng và DB đã có `rag_chunks` | OK, `Schema OK: vector(384)` |
| HTML → Markdown | bỏ hết nav/sidebar/footer/script/quảng cáo, giữ bảng, heading ATX |
| PDF → Markdown | heading 3 cấp đúng, **header + footer lặp bị loại**, giữ mốc trang |
| Chunk theo heading | đường dẫn heading đúng trên cả `.md` và `.html` |
| Bỏ qua file không đổi | `SKIPPED_UNCHANGED` |
| Full-text gõ **không dấu** | tìm ra text **có dấu** (10–11 hit) |
| Xếp hạng | đúng section cho 3/4 câu hỏi (câu còn lại sai do model embedding LOCAL) |
| Cổng từ chối | `RERANK_SCORE_BELOW_THRESHOLD`, `/chat` trả 200 mà **không gọi LLM** |
| Đổi cấu hình lúc runtime | áp dụng ngay, sống qua restart |
| Lưu hội thoại + feedback | OK |
| Job nạp thư mục | 3 file / 14 chunk / 367 ms |
| Chặn đường dẫn ngoài allowlist | `403` cho `C:/Windows/System32` |

**Chưa kiểm chứng được** (cần API key thật): sinh câu trả lời, streaming SSE đầu-cuối,
LLM/Cohere rerank, query rewrite, HyDE, eval — vì tất cả đều cần model ngoài.
Chất lượng xếp hạng cũng chỉ đo được đúng khi dùng embedding thật (OpenAI hoặc `bge-m3`);
model `LOCAL` all-MiniLM là model tiếng Anh, tiếng Việt kém.

---

## 8. Việc nên làm tiếp

1. **Đặt khoá thật và bỏ `RAG_ALLOW_ANONYMOUS`** trước khi cho người dùng vào.
2. **Chọn embedding production**: OpenAI `text-embedding-3-small` (1536) hoặc Ollama
   `bge-m3` (1024, tốt cho tiếng Việt, chạy nội bộ). Đổi `rag.embedding.dimensions`,
   tạo lại schema, nạp lại tài liệu.
3. **Bật Cohere rerank** nếu có key — chính xác hơn, rẻ và nhanh hơn LLM rerank,
   và cho điểm số thật để đặt ngưỡng từ chối.
4. **Xây bộ 20–50 câu hỏi chuẩn** rồi mới tinh chỉnh tham số. Không có nó thì mọi
   thay đổi đều là phỏng đoán.
5. **Viết test**: ưu tiên `MarkdownChunker` (bảng/heading/setext), `TsQueryBuilder`
   (stopword, ký tự đặc biệt), `RelevanceGate` (4 nhánh quyết định),
   `PromptBuilder.neutralize`. Đây là những chỗ lỗi âm thầm và khó phát hiện bằng mắt.
6. **OCR** nếu có tài liệu scan — hiện là vùng mù hoàn toàn.
