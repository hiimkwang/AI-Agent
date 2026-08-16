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

Đăng nhập bằng tài khoản công ty (**mặc định tắt** — tắt thì hệ thống chạy y như cũ
bằng API key):

```
ENTRA_ENABLED=true
ENTRA_TENANT_ID=...             # thiếu tenant/client id ⇒ ứng dụng TỪ CHỐI khởi động
ENTRA_CLIENT_ID=...
ENTRA_CLIENT_SECRET=...
ENTRA_BOOTSTRAP_ADMINS=ban@bsc.com.vn   # cửa hậu khởi động, xoá sau khi gán app role
```

Redirect URI phải khai trong app registration: `https://<host>/login/oauth2/code/entra`.

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
│                 TeamsProperties, EntraProperties, AppConfig (TransactionTemplate,
│                 @EnableScheduling)
├── security/     SecurityConfig (2 filter chain), ApiKeyAuthFilter, RateLimitFilter,
│                 AccessScope, PathAllowlist, TeamsSignatureVerifier, CurrentScope,
│                 AuthController (/me) + đăng nhập Entra: EntraClientRegistrationConfig,
│                 EntraOidcUserService, EntraScopeService, EntraScopeFilter,
│                 GraphDirectoryClient
├── common/       ApiExceptionHandler (ẩn chi tiết lỗi, sinh traceId), Hashes
├── llm/          LlmClient ← OpenAi/Anthropic/Gemini/Ollama + streaming,
│                 LlmClientFactory, InternalLlm, EmbeddingService, ModelPricing
├── ingest/       DocumentConverterService → converter/{Html,Pdf,Office}ToMarkdown,
│                 MarkdownChunker (bám heading), FrontMatter, ContextualEnricher,
│                 IngestionService, IngestionJobService
├── store/        ChunkRepository, DocumentRepository, ConversationRepository,
│                 FeedbackRepository, AnswerCacheRepository, JobRepository,
│                 EvalRepository, SettingsRepository, UsageReportRepository,
│                 SchemaValidator, TsQueryBuilder, Vectors
├── retrieval/    QueryPlanner (rewrite + HyDE), HybridRetriever (song song + RRF)
├── rerank/       Reranker ← LlmReranker | CohereReranker | Passthrough
├── chat/         RagChatService (đồng bộ + stream), RelevanceGate, PromptBuilder,
│                 AnswerCacheService, RagChatController, TeamsWebhookController (CŨ)
├── bot/          Bot Teams thật: TeamsBotController (/api/messages), BotAuthenticator
│                 (JWT Microsoft), BotConnectorClient (chiều ra), BotActivity,
│                 BotAccessResolver (phạm vi theo ngữ cảnh), TeamsBotService, AdaptiveCards
├── admin/        IngestionController, DocumentController, SystemController,
│                 RetrievalDebugController, ReportController (báo cáo theo bot)
├── platform/     Nhiều bot: PlatformModels, PlatformRepository, PlatformService
│                 (ảnh chụp trong bộ nhớ + định tuyến bot), PlatformAdminController
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
Thiết kế nền tảng nhiều bot + tích hợp Teams + phân quyền Entra:
[docs/BOT-PLATFORM.md](docs/BOT-PLATFORM.md).
Hướng dẫn bật đăng nhập bằng tài khoản công ty: [docs/ENTRA-SETUP.md](docs/ENTRA-SETUP.md).

## Bất biến — phá là hỏng dữ liệu

- **Vector câu hỏi và vector tài liệu phải cùng một model.** Đổi
  `rag.embedding.provider` / `dimensions` ⇒ tạo lại schema và **nạp lại toàn bộ**.
  Số chiều được truyền vào Flyway qua placeholder `${embeddingDim}`, nên đổi cấu hình
  là đổi luôn DDL — `SchemaValidator` báo lỗi lúc khởi động nếu lệch.
  Đo trước khi đổi bằng `rag.embedding.trial.*` — xem
  [docs/EMBEDDING-UPGRADE.md](docs/EMBEDDING-UPGRADE.md). Và **xoá `rag_answer_cache`**
  sau khi đổi: cache giữ vector câu hỏi theo model cũ, để lại thì cache semantic so
  vector khác hệ.
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
- **`AccessScope.cacheScopeKey()` phải gồm CẢ phần role**, không chỉ phòng ban.
  `HybridRetriever` lọc `allowed_roles` bằng `scope.isAdmin() ? Set.of() : scope.roles()`,
  nên ADMIN thấy cả tài liệu hạn chế. Khoá cache thiếu role ⇒ câu trả lời sinh cho ADMIN
  được phục vụ lại cho USER. Cache semantic (cosine ≥ 0.97) làm rò rỉ này nặng hơn vì
  không cần câu hỏi giống hệt. Xem `AccessScopeTest`.
- **Không lấy được nhóm Entra = KHÔNG có quyền nào**, không phải "có mọi quyền".
  `GraphDirectoryClient.memberGroups` trả rỗng khi Graph lỗi, và `EntraScopeService`
  hiểu rỗng là đóng. Đừng "sửa" thành mở toàn bộ khi Graph chết.
- **Bot được ghi vào `rag_messages.bot_slug` bằng SLUG, không phải khoá ngoại.** Xoá một
  bot không được làm mất số liệu lịch sử của nó — đó đúng là thứ cần để giải trình. Slug
  nằm ở mức *tin nhắn* (không chỉ ở `rag_conversations.bot_id`) để câu báo cáo chỉ đọc một
  bảng, và để một hội thoại đổi bot không bị quy toàn bộ lịch sử cho bot mới.
- **`rag_collections.slug` CHÍNH LÀ cột `category`.** V3 cố ý không thêm khoá ngoại
  `collection_id` vào `rag_chunks` để không phải sửa một dòng SQL tìm kiếm nào. Mọi đường
  ghi `category` phải đi qua `PlatformService`, vì DB không còn ràng buộc giúp.
- **Trong channel Teams phải HẠ quyền ADMIN xuống USER.** Chỉ thu hẹp danh sách
  collection là chưa đủ: `HybridRetriever` lọc `allowed_roles` bằng
  `isAdmin() ? Set.of() : roles()`, nên một quản trị viên hỏi trong channel sẽ kéo tài
  liệu hạn chế ra cho cả kênh. Xem `BotAccessResolver`.
- **Persona của bot chèn TRƯỚC các quy tắc bắt buộc trong system prompt, không phải sau.**
  Mô hình chịu ảnh hưởng mạnh nhất bởi phần cuối prompt; đặt persona ở cuối cho phép một
  dòng cấu hình vô hiệu hoá quy tắc "chỉ trả lời theo tài liệu".
- **Mọi chuỗi cùng một tên metric phải có CÙNG bộ khoá tag.** Prometheus loại bỏ **im
  lặng** chuỗi lệch tag — không một dòng log nào, số liệu chỉ đơn giản biến mất khỏi
  `/actuator/prometheus`. Đã xảy ra thật với `rag.latency`: `stage=total` được gắn thêm
  tag `bot` còn ba bước kia thì không. Vì vậy `RagMetrics.stage()` gắn cả `stage` lẫn
  `bot` cho mọi timer; xem `RagMetricsTest.everyLatencySeriesHasTheSameTagKeys`.
  Nhãn bot **không bao giờ để rỗng** — đường web mang nhãn `"web"`.
- **Rỗng có nghĩa ngược nhau ở hai chỗ, và đó là chủ ý.** ACL collection rỗng = *đóng*
  (không ai đọc được). Đối tượng sử dụng bot rỗng = *mở* (ai cũng dùng được). Lý do: cấm
  dùng bot không bảo vệ dữ liệu — dữ liệu được ACL collection bảo vệ.
- **`mergeTinySections` KHÔNG được gộp qua ranh giới `Điều`.** Một `Điều` ngắn bị gộp vào
  `Điều` kế tiếp sẽ mang nhãn của `Điều` sau (bước chọn "đường dẫn cụ thể hơn") ⇒ câu trả
  lời trích dẫn sai căn cứ mà vẫn đọc rất xuôi. Lỗi này đã xảy ra thật; xem
  `LegalStructureChunkerTest.shortArticleIsNotMergedIntoTheNextOne`. Test cho chunker phải
  dùng `min-section-chars` mặc định, đặt 0 là làm test mất khả năng bắt lỗi.
- Đổi `rag.chunking.*` (kể cả `legal-structure-enabled`, `prefix-document-identity`)
  ⇒ phải **nạp lại tài liệu** mới có tác dụng. Chỉ `rag.retrieval.*` mới áp dụng ngay.

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
- **Chain API dùng `SessionCreationPolicy.NEVER`, KHÔNG phải `STATELESS`.** `STATELESS`
  bỏ qua cả phiên sẵn có ⇒ trình duyệt đăng nhập Entra xong vẫn bị 401 ở mọi lời gọi API.
- **`ApiKeyAuthFilter` không được `clearContext()` khi request không kèm API key** —
  làm vậy là xoá luôn phiên OIDC vừa nạp từ session. Chỉ xoá context do chính nó đặt.
- **Không khai `spring.security.oauth2.client.registration.*` với mặc định rỗng.**
  Spring Boot coi chuỗi rỗng là "đã cấu hình" và ném lỗi lúc khởi động khi chưa ai bật
  SSO. `EntraClientRegistrationConfig` dựng `ClientRegistration` bằng code, có điều kiện
  `rag.entra.enabled=true`, và không dùng `fromIssuerLocation` (gọi mạng lúc khởi động).
- CSRF chỉ bật khi bật SSO, và **miễn trừ cho request có `X-API-Key`** — CSRF chỉ nguy
  hiểm với xác thực bằng cookie; bật cho đường API key chỉ làm hỏng script mà không
  đổi lại được gì.

## Khi thay đổi hành vi trả lời

1. Có bộ câu hỏi chuẩn. **Không phải ngồi gắn nhãn tay** — bộ 100 câu thật chỉ có sau
   vài tháng vận hành, bắt phải có trước là bài toán con gà–quả trứng:
   - `POST /eval/cases/generate` — sinh từ chính kho tài liệu, nguồn đúng là file chứa
     đoạn đó nên nhãn có sẵn. Dùng được ngay ngày đầu. Câu sinh ra *dễ hơn* câu thật nên
     recall tuyệt đối bị thổi lên, nhưng độ lệch đó tác động như nhau lên mọi cấu hình
     đem ra so ⇒ vẫn dùng để **chọn cấu hình** được.
   - `POST /eval/cases/harvest` — thu hoạch câu hỏi thật từ `rag_messages`. Nhãn là
     "hệ thống đã tìm ra cái gì" ⇒ đo **hồi quy**, không đo được cái vốn đã sai.
   - `POST /eval/cases/harvest {"negative":true}` — câu bị 👎 vào bộ riêng, chưa có nhãn.
   - `POST /eval/cases` — thêm tay, vẫn tốt nhất nhưng là thứ **bổ sung dần**.
2. Đo baseline. Hai phép đo khác nhau, dùng đúng loại:
   - `POST /api/v1/rag/eval/retrieval` — **recall@k + MRR**, không sinh câu trả lời,
     không giám khảo LLM. Rẻ và deterministic ⇒ chạy sau *mỗi* lần đổi tham số truy
     xuất (chunking, trọng số `tsv`, model embedding, top-k, từ điển).
     `includeRerank=true` cho biết bộ rerank đang làm tốt lên hay **làm hỏng** thứ tự.
   - `POST /api/v1/rag/eval/run` — faithfulness/answer-relevance, tốn một lần gọi LLM
     giám khảo cho **mỗi** case. Chỉ chạy khi đổi thứ gì ảnh hưởng câu trả lời.
3. Đổi tham số qua `POST /api/v1/rag/settings` (**áp dụng ngay, không restart**).
4. Chạy lại eval, so điểm — tham số của từng lần chạy được lưu kèm kết quả.

Muốn biết *vì sao* một câu trả lời sai: `POST /api/v1/rag/admin/retrieval-test`
trả về từng bước (biến thể truy vấn, kết quả mỗi nhánh, điểm RRF, điểm rerank,
quyết định của cổng từ chối) mà không sinh câu trả lời.
