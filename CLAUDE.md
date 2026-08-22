# AI-Agent — RAG Document System

Spring Boot service cung cấp chatbot hỏi–đáp trên tài liệu nội bộ (RAG) cho BSC,
kèm hai giao diện web: màn hỏi–đáp (`/`) và màn quản trị (`/admin.html`).
**Log ứng dụng: tiếng Anh.** Chữ hiện ra cho người dùng và người vận hành (thông báo API,
toast, thẻ Teams, nội dung màn quản trị): **tiếng Việt**. Comment trong code và file cấu
hình: **tiếng Anh, ngắn gọn** — chỉ viết khi giải thích điều không đọc được từ code.

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
├── audit/        AuditFilter (ghi TỰ ĐỘNG mọi thao tác đổi dữ liệu/cấu hình, kể cả
│                 401/403), AuditService (ghi nền), AuditRepository, AuditController
├── retention/    RetentionService (@Scheduled dọn hội thoại/nhật ký/job quá hạn)
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
Triển khai, sao lưu/khôi phục, nhật ký kiểm toán, vòng đời dữ liệu, OCR, quét virus:
[docs/OPERATIONS.md](docs/OPERATIONS.md).
Phương án triển khai lên máy chủ UAT cụ thể của BSC (10.21.170.55, cạnh Drupal sẵn có):
[docs/DEPLOY-UAT.md](docs/DEPLOY-UAT.md).
Bộ cài systemd cho máy chủ: [aiagent/](aiagent) — `bin/Linux/install.sh` sinh unit
file từ `bin/Linux/deploy.env`. Vận hành **chỉ sửa `config/aiagent.env`**, file này
được cả `aiagent.service` lẫn `rag-postgres.service` đọc nên mật khẩu CSDL khai một
lần. `config/application.properties` chỉ là bản đồ `khoá=${BIEN}`, không giữ giá trị —
ghi giá trị cứng vào đó là giết placeholder và vô hiệu biến trong `aiagent.env`.
`deploy/aiagent.service` là bản đời trước — đừng cài cả hai.

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
  Cụ thể: `IngestionService.ingest` gọi `PlatformService.ensureCollection` sau khi ghi
  tài liệu — **đừng gỡ**. Không có nó, quét thư mục sinh ra category theo tên thư mục con
  mà không nhóm nào khai, và toàn bộ tài liệu đó chỉ quản trị viên đọc được, âm thầm
  (đã xảy ra thật: 201 tài liệu / 17 category / 0 collection). Nhóm tự khai luôn ở trạng
  thái **ACL rỗng = đóng**: nó chấm dứt trạng thái mồ côi chứ không tự mở dữ liệu cho ai.
- **Đổi `category` của tài liệu đã nạp phải đổi CẢ BA thứ cùng lúc**: `rag_documents.category`,
  `rag_documents.doc_key` (vì `doc_key = category/fileName` là khoá ghi đè — bỏ sót thì lần
  nạp sau tạo bản ghi thứ hai thay vì ghi đè), và `rag_chunks.category` + `rag_chunks.doc_id`
  (bộ truy xuất lọc trên bản sao ở chunk, không phải ở document). Xem
  `DocumentController.moveCategory`.
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
- **Lịch sử hội thoại phải đến CẢ hai nơi: bộ viết lại câu hỏi VÀ model trả lời.** Bản cũ chỉ
  đưa vào `QueryPlanner` — truy xuất được hưởng lợi, còn model viết câu trả lời nhận đúng câu
  gốc trần trụi. Đo trên UAT: hỏi "Lệnh điều kiện OCO" rồi hỏi tiếp "Lệnh cơ sở ấy" ⇒ *"Tôi
  không tìm thấy thông tin này trong tài liệu nội bộ"*. Hai cửa sổ lịch sử là hai tham số khác
  nhau và **cố ý** khác: `rag.query-rewrite.max-history-turns` (6) đủ để giải đại từ,
  `rag.chat.history-turns` (3) ngắn hơn vì mỗi lượt bị tính tiền ở *mọi* câu hỏi. Lượt của trợ
  lý phải bị cắt (`history-chars-per-turn`), nếu không câu trả lời 1500 ký tự sẽ lấn át chính
  đoạn tài liệu nó đứng cạnh.
- **Câu đã viết lại là GỢI Ý đi kèm câu gốc, không thay thế câu gốc.** Bộ viết lại chạy bằng
  `internal.model` (model rẻ) và sai đủ thường xuyên: "Lệnh cơ sở ấy" — câu hỏi tiếp nối về
  OCO — bị viết lại thành "Lệnh cơ sở là gì?", tức là **vứt đi đúng cái tham chiếu** mà nó
  được giao nhiệm vụ giải. Đưa cả hai vào prompt thì model tự giải tham chiếu, nó làm việc đó
  tốt hơn bộ viết lại.
- **Khoá cache phải gồm ngữ cảnh hội thoại khi có lịch sử.** Không thì câu hỏi tiếp nối rò rỉ
  giữa các hội thoại: "Lệnh cơ sở ấy" được lưu chỉ bằng bốn chữ đó, người tiếp theo gõ đúng
  bốn chữ ấy trong một hội thoại về chuyện khác sẽ nhận câu trả lời của hội thoại này. Cache
  semantic (0.97) làm nặng thêm vì câu hỏi tiếp nối đều ngắn và na ná nhau. Câu hỏi **đầu
  tiên** không có ngữ cảnh nên vẫn dùng chung một ô — đó cũng là chỗ cache thực sự có giá trị.
  Vì vậy `RagChatService.prepare` đọc lịch sử **trước** khi tra cache; đừng đảo lại thứ tự.
- **Câu từ chối KHÔNG được vào `rag_answer_cache`.** System prompt dạy mô hình nói "Tôi không
  tìm thấy thông tin này trong tài liệu nội bộ" khi đoạn trích chưa đủ — và đó đúng là câu
  không được đóng băng: tài liệu vẫn được nạp thêm, tham số truy xuất vẫn được chỉnh, nên
  "không tìm thấy" hôm nay là câu **sai** ngày mai, phục vụ lại trong 200 ms mà người dùng
  không có cách nào biết là đồ cũ. Cache semantic (cosine ≥ 0.97) phát tán nó sang cả câu hỏi
  gần giống. `AnswerCacheService.looksLikeRefusal` chặn ở `store`; bắt nhầm chỉ tốn một lần
  cache miss, bỏ sót thì hỏng cả TTL — nên mẫu regex cố ý rộng. Đã xảy ra thật với câu
  "Lệnh STO". Xem `AnswerCacheRefusalTest`.
- **Bấm 👎 phải xoá luôn bản cache của câu hỏi đó**, nếu không hỏi lại vẫn ra đúng câu cũ và
  nút phản hồi trông như bị hỏng. Xoá theo *nội dung câu hỏi*, mọi phạm vi — câu trả lời tệ
  thì tệ với tất cả, và cache là bộ tăng tốc chứ không phải hàng rào phân quyền.
- **Mục lục tài liệu Word là rác truy xuất, phải loại.** Vài trang "2.2.5 Giải pháp thực hiện
  21" chunk ra như văn bản thường, embedding gần *mọi* câu hỏi về tài liệu đó vì nó nhắc lại
  toàn bộ tên chương mục, và không trả lời được gì. Đo trên UAT: một đoạn như vậy được rerank
  chấm **0.92 — ô cao nhất** — cho câu "Lệnh STO", đẩy bản đặc tả thật xuống dưới.
  `TableOfContentsFilter` lọc sau RRF (áp dụng ngay cho tài liệu đã nạp, không cần nạp lại).
  Nhận diện cố ý hẹp: phải có số mục nhiều cấp hoặc dấu chấm nối, **kèm** số trang cuối dòng,
  trên ≥60% số dòng và ít nhất 5 dòng. Loại sạch thì giữ nguyên danh sách gốc — trả về rỗng
  sẽ thành câu từ chối, tệ hơn là để cổng lọc tự quyết.
  **Quét bằng tay, KHÔNG dùng regex** — và đó là kinh nghiệm phải trả giá: bản đầu dùng
  `^\s*\d+(?:\.\d+)+\.?\s+\S.*?\s+\d{1,4}\s*$`; `.*?` đứng trước một mỏ neo cuối dòng
  quay lui theo **bình phương** độ dài với mọi dòng KHÔNG khớp — tức là gần hết. Đo trên UAT:
  `retrievalMs` từ 596 ms vọt lên **26.000 ms**, không một dòng log lỗi nào.
  `TableOfContentsFilterTest.aLongNonMatchingChunkIsFast` chốt lại chuyện này.
- **Câu từ chối do CHÍNH MODEL viết phải được đối xử như một lần cổng từ chối.** Cổng có thể cho
  đoạn trích đi qua rồi model vẫn kết luận là không đủ; với người hỏi thì hai thứ đó y hệt nhau.
  Vì vậy `finishGenerated` phát hiện câu từ chối (dùng lại `looksLikeRefusal`) rồi: **bỏ danh
  sách nguồn** (liệt kê 6 nguồn ngay dưới câu "tôi không tìm thấy" trông như lỗi — đúng cái ảnh
  chụp màn hình đã cho thấy), gắn `suggestions` lấy từ heading của chính các ứng viên đã truy
  xuất, và chỉ chèn câu hỏi lại khi không gợi ý được gì cụ thể. Gợi ý là **chip bấm được** nên
  phải đọc ra một câu hỏi: `tidyTopic` cắt số mục đầu dòng, dấu hai chấm cuối, mã số, và bỏ
  mẩu một từ.
- **Không khớp đường dẫn bằng `getRequestURI()`. Dùng `RequestPaths.within(request)`.**
  `getRequestURI()` **có** cả context path, nên ứng dụng chạy dưới tiền tố
  (`server.servlet.context-path=/rag` trên UAT) sẽ làm mọi phép so đường dẫn **hỏng theo
  chiều mở**, im lặng: `RateLimitFilter` tắt hẳn giới hạn tần suất, `AuditFilter` ngừng ghi
  nhật ký, miễn trừ CSRF cho `/api/messages` không còn hiệu lực nên bot Teams bị chặn.
  Không một dòng log nào báo. Xem `RequestPathsTest`.
- **Giao diện web phải chạy được ở cả gốc lẫn dưới tiền tố.** Mọi đường dẫn tuyệt đối
  trong `static/` đi qua `url()` trong `app.js`, hàm này lấy tiền tố từ chính `src` của
  `app.js`. Thêm `fetch('/api/...')` trần là chạy đúng khi dev ở gốc và **404 trên máy chủ**.
  Tài nguyên trong HTML dùng đường dẫn tương đối (`app.css`, `app.js`, `./`), không dùng `/`.
- **Nhật ký kiểm toán ghi bằng FILTER, không bằng lời gọi trong từng controller.**
  `AuditFilter` nằm ngay trước `ExceptionTranslationFilter` nên bắt được cả thao tác
  **bị từ chối** (401/403) — đó mới là thứ cần nhất khi điều tra. Thêm endpoint quản
  trị mới thì **không phải làm gì**; đừng "cải tiến" thành gọi tay ở từng nơi, vì chỗ
  bị quên chính là chỗ cần nhất. Nhật ký **chỉ có đường đọc**.
- **Quét virus đặt ở `IngestionService.ingest`, không ở controller.** Đó là điểm nghẽn
  duy nhất của cả ba đường nạp; đặt ở controller thì đường nạp thêm sau sẽ bị bỏ sót.
  `fail-closed=true` là cố ý: không kết nối được clamd ⇒ **từ chối** file.
- **PDF bản scan không nạp được nếu `rag.ocr.enabled=false`** — job tính là *thất bại*
  kèm cảnh báo chứ không im lặng bỏ qua. OCR tốn **một lời gọi model mỗi trang**, nên
  `rag.ocr.max-pages` là cái chặn thật, đừng gỡ.

## Quy ước code

- Comment **tiếng Anh, ngắn**, chỉ giải thích *tại sao* khi code không tự nói được.
  Không viết đoạn văn dài, không kể lại lịch sử sửa lỗi trong comment.
- **Log bằng tiếng Anh.** Mẫu logback đã in `class.method.line`, nên **không** lặp lại tên
  thành phần trong thông điệp (`log.warn("Bot: ...")` là thừa). Mức log:
  `ERROR` hỏng thật cần người xử lý (kèm throwable khi là lỗi không lường trước) ·
  `WARN` bước phụ trợ hỏng nhưng đi tiếp, cấu hình thiếu, phụ thuộc ngoài lỗi ·
  `INFO` sự kiện vòng đời và mỗi request một dòng ·
  `DEBUG` mọi thứ theo khối lượng.
  **Nội dung câu hỏi/câu trả lời/đoạn tài liệu chỉ được ghi ở mức `DEBUG`**, và phải qua
  cả cờ cấu hình lẫn `log.isDebugEnabled()` — đó là dữ liệu nội bộ, không phải log vận hành.
  Câu trả lời đầy đủ và danh sách ứng viên truy xuất từng ở `INFO`: một câu hỏi sinh hàng
  chục dòng và đổ nguyên văn tài liệu vào log.
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
