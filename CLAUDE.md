# AI-Agent — RAG Document System

Spring Boot service cung cấp chatbot hỏi–đáp trên tài liệu nội bộ (RAG) cho BSC,
kèm hai giao diện web: màn hỏi–đáp (`/`) và màn quản trị (`/admin.html`).
Ngôn ngữ nghiệp vụ, Javadoc và log trong repo này là **tiếng Việt** — giữ nguyên quy ước đó.

## Stack

| Thành phần | Lựa chọn |
|---|---|
| Runtime | Java 21, Spring Boot **3.5.9** |
| Bảo mật | Spring Security, xác thực bằng API key (`X-API-Key`), role `ADMIN`/`USER` |
| DB | PostgreSQL 17 + pgvector, schema quản lý bằng **Flyway** |
| Chat LLM | **Claude** (SDK `com.anthropic:anthropic-java`), OpenAI, Gemini (REST), Ollama |
| Embedding | OpenAI `text-embedding-3-small` · Ollama (`bge-m3`) · **LOCAL** all-MiniLM ONNX |
| Rerank | LLM · Cohere · NONE |
| Chuyển đổi tài liệu | PDFBox 3 (PDF), jsoup + flexmark (HTML), Apache POI 5.4 (Office) |
| Cache | Caffeine (rate limit) + bảng `rag_answer_cache` (exact + semantic) |
| Quan sát | Actuator + Micrometer/Prometheus |
| LLM framework | LangChain4j **0.31.0** — chỉ dùng cho OpenAI/Ollama chat + embedding |

## Chạy

```powershell
docker compose up -d                 # Postgres 17 + pgvector
./mvnw spring-boot:run               # dev
./mvnw clean package                 # build (test bị skip theo mặc định của pom)
java -jar target/AIAgent-0.2.0.jar   # chạy jar
```

Biến môi trường tối thiểu:

```
DB_PASSWORD=...                 # bắt buộc (không còn hardcode trong properties)
RAG_ADMIN_API_KEY=...           # bắt buộc, nếu không mọi request bị 401
RAG_USER_API_KEY=...
OPENAI_API_KEY=...              # hoặc ANTHROPIC_API_KEY / GEMINI_API_KEY
RAG_ALLOWED_ROOTS=D:/tai-lieu   # bắt buộc nếu muốn dùng /admin/ingest-folder
```

Chạy **không cần API key nào** (thử pipeline offline):

```
RAG_ALLOW_ANONYMOUS=true RAG_EMBEDDING_PROVIDER=LOCAL RAG_EMBEDDING_DIM=384 RAG_RERANK_PROVIDER=NONE
```

`LOCAL` dùng all-MiniLM-L6-v2 ONNX chạy trong tiến trình — **chất lượng tiếng Việt kém**,
chỉ để kiểm tra pipeline. Production dùng OpenAI hoặc Ollama `bge-m3`.

## Bản đồ code

```
com.ai.aiagent
├── config/       RagProperties (@ConfigurationProperties, MUTABLE), SecurityProperties,
│                 TeamsProperties, AppConfig (TransactionTemplate, @EnableScheduling)
├── security/     SecurityConfig, ApiKeyAuthFilter, RateLimitFilter, AccessScope,
│                 PathAllowlist, TeamsSignatureVerifier, CurrentScope
├── common/       ApiExceptionHandler (ẩn chi tiết lỗi, sinh traceId), Hashes
├── llm/          LlmClient ← OpenAi/Anthropic/Gemini/Ollama + streaming,
│                 LlmClientFactory, InternalLlm, EmbeddingService, ModelPricing
├── ingest/       DocumentConverterService → converter/{Html,Pdf,Office}ToMarkdown,
│                 MarkdownChunker (bám heading), FrontMatter, ContextualEnricher,
│                 IngestionService, IngestionJobService
├── store/        ChunkRepository, DocumentRepository, ConversationRepository,
│                 FeedbackRepository, AnswerCacheRepository, JobRepository,
│                 EvalRepository, SettingsRepository, SchemaValidator,
│                 TsQueryBuilder, Vectors
├── retrieval/    QueryPlanner (rewrite + HyDE), HybridRetriever (song song + RRF)
├── rerank/       Reranker ← LlmReranker | CohereReranker | Passthrough
├── chat/         RagChatService (đồng bộ + stream), RelevanceGate, PromptBuilder,
│                 AnswerCacheService, RagChatController, TeamsWebhookController
├── admin/        IngestionController, DocumentController, SystemController,
│                 RetrievalDebugController
├── settings/     RagSettingsService (đổi cấu hình lúc runtime), Controller
├── eval/         EvalService, EvalController
└── observability/RagMetrics
resources/
├── db/migration/V1__rag_core_schema.sql   ← placeholder ${embeddingDim}
└── static/       index.html (chat), admin.html, app.css, app.js
```

Ba file đọc trước khi sửa bất cứ thứ gì về chất lượng trả lời:
[RagChatService.java](src/main/java/com/ai/aiagent/chat/RagChatService.java),
[HybridRetriever.java](src/main/java/com/ai/aiagent/retrieval/HybridRetriever.java),
[MarkdownChunker.java](src/main/java/com/ai/aiagent/ingest/MarkdownChunker.java).
Toàn bộ SQL tìm kiếm nằm trong
[ChunkRepository.java](src/main/java/com/ai/aiagent/store/ChunkRepository.java).

Mô tả kiến trúc đầy đủ: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Bất biến — phá là hỏng dữ liệu

- **Vector câu hỏi và vector tài liệu phải cùng một model.** Đổi
  `rag.embedding.provider` / `dimensions` ⇒ tạo lại schema và **nạp lại toàn bộ**.
  Số chiều được truyền vào Flyway qua placeholder `${embeddingDim}`, nên đổi cấu hình
  là đổi luôn DDL — `SchemaValidator` báo lỗi lúc khởi động nếu lệch.
- **`doc_key` = `category/fileName`** là khoá ghi đè. Hai file cùng tên ở hai category
  khác nhau là **hai tài liệu khác nhau** (đây là cố ý, sửa lỗi ghi đè âm thầm cũ).
- **Cột `tsv` do TRIGGER `trg_rag_chunks_tsv` sinh**, không phải code Java. Đừng ghi tay.
- **Text search config là `'vi'`** (`simple` + `unaccent`), không phải `'simple'`.
  Nhờ vậy gõ không dấu vẫn tìm ra. Đổi config ⇒ phải `REINDEX`.
- **Full-text ghép các từ bằng OR** (`TsQueryBuilder.orQuery`), không phải AND.
  Đừng quay lại `plainto_tsquery` — đó là lý do nhánh full-text từng vô dụng.
- **`Set.of()` không nhận phần tử trùng.** Danh sách stopword Việt/Anh có từ trùng nên
  phải dùng `Set.copyOf(List.of(...))`, nếu không static initializer sẽ nổ
  `ExceptionInInitializerError` và giết cả nhánh full-text.
- **Postgres + `RETURN_GENERATED_KEYS` trả về MỌI cột** ⇒ `KeyHolder.getKey()` nổ.
  Luôn dùng `prepareStatement(sql, new String[]{"id"})`.
- **Parent–child**: tìm bằng `content` (child), trả lời bằng `parent_content`.

## Quy ước code

- Javadoc và log **tiếng Việt**, giải thích *tại sao*, và khi sửa lỗi cũ thì nói rõ
  lỗi đó là gì (xem `RelevanceGate`, `TsQueryBuilder`, `Reranker` làm mẫu).
- Tham số hoá qua `rag.*` trong `RagProperties`. **Không dùng `@Value` vào field**
  cho tham số cần tune — `RagProperties` là bean mutable để `RagSettingsService`
  đổi được lúc runtime.
- Constructor injection. `@Slf4j`.
- **Mỗi bước phụ trợ phải fallback im lặng** (rewrite, HyDE, sinh context, cache,
  lưu hội thoại): lỗi thì log `warn` và đi tiếp, không làm mất câu trả lời.
- **Nhưng bộ rerank thì KHÔNG**: phải phân biệt "không có gì liên quan"
  (`RerankResult.reliable` + rỗng ⇒ từ chối trả lời) với "bộ rerank bị lỗi"
  (`degraded` ⇒ `RelevanceGate` chuyển sang đánh giá bằng cosine). Đây là lỗi
  nghiêm trọng nhất của bản cũ, đừng làm lại.
- Hai nhánh retrieval phải **thất bại độc lập** (`HybridRetriever.joinSafely`).
- Không bao giờ trả `e.getMessage()` của lỗi không lường trước ra client.
- Nội dung tài liệu đi vào prompt phải qua `PromptBuilder.neutralize`.

## Cạm bẫy đã biết

- `@Transactional` **không có tác dụng** khi gọi nội bộ cùng class (self-invocation).
  `IngestionService` dùng `TransactionTemplate` vì lý do này.
- LangChain4j 0.31 chưa có module Google AI Gemini dùng API key → `GeminiLlmClient`
  gọi REST tay. Đừng "sửa" thành module chính thức mà không nâng LangChain4j.
- **Claude Opus 5 không nhận `temperature`** (trả 400) và bật thinking theo mặc định.
  Tắt thinking chỉ hợp lệ khi `effort <= high` — `AnthropicLlmClient` tự hạ effort.
- flexmark mặc định sinh heading **setext** (`Tiêu đề` + `====`). Phải tắt
  (`SETEXT_HEADINGS=false`), nếu không `MarkdownChunker` không thấy heading nào
  trong tài liệu chuyển từ HTML.
- pgvector áp filter **sau** khi duyệt HNSW ⇒ khi lọc theo category/ACL phải
  over-fetch (`rag.retrieval.filter-overfetch-multiplier`) rồi cắt lại ở Java.
- PowerShell 5.1 đọc file `.ps1` không BOM theo ANSI ⇒ chuỗi tiếng Việt bị hỏng.
  Test API tiếng Việt thì để JSON trong file UTF-8 rồi `curl --data-binary @file`.

## Khi thay đổi hành vi trả lời

1. Thêm 20–50 câu hỏi thật vào bộ chuẩn: `POST /api/v1/rag/eval/cases`.
2. Chạy `POST /api/v1/rag/eval/run`, ghi lại điểm.
3. Đổi tham số qua `POST /api/v1/rag/settings` (**áp dụng ngay, không restart**).
4. Chạy lại eval, so điểm — tham số của từng lần chạy được lưu kèm kết quả.

Muốn biết *vì sao* một câu trả lời sai: `POST /api/v1/rag/admin/retrieval-test`
trả về từng bước (biến thể truy vấn, kết quả mỗi nhánh, điểm RRF, điểm rerank,
quyết định của cổng từ chối) mà không sinh câu trả lời.
