# AI-Agent — Mô hình hoạt động và chức năng

Tài liệu mô tả hệ thống **đúng như nó đang chạy** trên UAT `10.21.170.55` ngày 19/08/2026,
không phải thiết kế dự kiến. Mọi con số trong mục [11](#11-trạng-thái-thực-tế-trên-uat) đều
lấy từ máy chủ thật.

Tài liệu liên quan: [ARCHITECTURE.md](ARCHITECTURE.md) (lý do thiết kế),
[DEPLOY-UAT.md](DEPLOY-UAT.md) (triển khai), [AZURE-SETUP-UAT.md](AZURE-SETUP-UAT.md) (Entra +
bot), [BOT-PLATFORM.md](BOT-PLATFORM.md) (nhiều bot), [OPERATIONS.md](OPERATIONS.md) (vận hành).

---

## 1. Hệ thống làm gì

Trợ lý hỏi–đáp trên **kho tài liệu nội bộ** của BSC. Ba tính chất quyết định mọi lựa chọn
kỹ thuật bên dưới:

| Tính chất | Nghĩa là |
|---|---|
| **Chỉ trả lời theo tài liệu** | Không dùng kiến thức chung của mô hình. Không tìm thấy căn cứ ⇒ nói "không tìm thấy", không đoán |
| **Luôn kèm nguồn** | Mỗi câu trả lời trích dẫn tên file + đường dẫn mục, để người đọc tự kiểm chứng |
| **Đọc theo quyền** | Hai người khác phòng hỏi cùng một câu có thể nhận hai kết quả khác nhau |

Ba đường vào: **web hỏi–đáp**, **web quản trị**, và **bot Microsoft Teams**. Cả ba dùng
chung một lõi RAG, khác nhau ở cách xác định danh tính và phạm vi đọc.

---

## 2. Kiến trúc triển khai hiện tại

```
Internet
   │  https://chatbot-uat.bsc.com.vn
   ▼
103.219.180.171          IP công cộng của BSC
   ▼
10.21.170.54             kết thúc TLS (cert GlobalSign, hạn 02/2027)
   ▼
10.21.170.55:80          Apache 2.4 ─┬─ /       → Drupal (không đụng tới)
                                     └─ /rag/   → 127.0.0.1:8080/rag/
                                                       │
        systemd  aiagent.service ──────────────────────┘   jar Spring Boot, JDK 21
        systemd  rag-postgres.service                      podman, pgvector/pgvector:pg17
                                                           chỉ bind 127.0.0.1:5432
```

**Tiền tố `/rag` do ứng dụng sinh ra** (`SERVER_CONTEXT_PATH=/rag`), không phải Apache thêm
vào — Apache chuyển `/rag/` sang `:8080/rag/` **giữ nguyên tiền tố**. Hệ quả: tiền tố có mặt
cả khi SSH tunnel nối thẳng cổng 8080. Nó phải khớp ở 4 nơi: `aiagent.env`, `chatbot.conf`,
`deploy.env` (HEALTH_URL) và Redirect URI trên Entra.

Toàn bộ ứng dụng nằm trong `/app/aiagent`: `deploy/` (jar), `config/` (aiagent.env — chỗ
**duy nhất** chứa giá trị và bí mật), `logs/`, `work/{tai-lieu,backup}`, `lib/jdk-21.0.11`,
`bin/Linux/` (bộ cài sinh unit systemd).

---

## 3. Luồng hỏi–đáp — chi tiết từng bước

Một câu hỏi đi qua 12 chặng. Cột "hỏng thì sao" là phần quan trọng nhất: **mọi bước phụ trợ
đều fallback im lặng**, chỉ bộ rerank là không.

| # | Bước | Thành phần | Hỏng thì sao |
|---|---|---|---|
| 1 | Xác thực + phạm vi | `ApiKeyAuthFilter` / `EntraScopeFilter` → `AccessScope` | 401/403, ghi nhật ký kiểm toán |
| 2 | Giới hạn tần suất | `RateLimitFilter` (Caffeine) | 429 |
| 3 | **Nhận diện xã giao** | `SmallTalkDetector` | Không khớp ⇒ đi tiếp bình thường |
| 4 | Nhúng câu hỏi | `EmbeddingService` | Lỗi ⇒ bỏ qua cache semantic, vẫn chạy tiếp |
| 5 | Tra cache | `AnswerCacheService` (exact + semantic cosine ≥ 0.97) | Lỗi ⇒ bỏ qua |
| 6 | Đọc lịch sử hội thoại | `ConversationRepository` | Lỗi ⇒ coi như không có ngữ cảnh |
| 7 | Lập kế hoạch truy vấn | `QueryPlanner`: viết lại + từ điển thuật ngữ + HyDE | Lỗi ⇒ dùng nguyên câu gốc |
| 8 | **Truy xuất lai** | `HybridRetriever`: vector ∥ full-text, hợp nhất bằng RRF | Hai nhánh **thất bại độc lập** (`joinSafely`) |
| 9 | **Xếp hạng lại** | `Reranker`: LLM / Cohere / Passthrough | **KHÔNG fallback im lặng** — xem dưới |
| 10 | **Cổng liên quan** | `RelevanceGate` | Quyết định trả lời hay từ chối |
| 11 | Dựng prompt + sinh | `PromptBuilder` → `LlmClient` | Lỗi ⇒ trả lỗi có traceId, không lộ chi tiết |
| 12 | Hậu kiểm + lưu | Kiểm tra trích dẫn, lưu hội thoại, ghi metrics | Lỗi lưu ⇒ vẫn trả lời |

### 3.1 Bước 3 — nhận diện xã giao (mới)

Lời chào **không khớp tài liệu nào**, nên trước đây nó rơi xuống cổng liên quan và bị trả lời
bằng *"không tìm thấy tài liệu"* — vô lý với câu "hello". Nay `SmallTalkDetector` chặn trước
khi truy xuất:

- Khớp **toàn bộ** tin nhắn và tin nhắn phải ngắn (≤ 64 ký tự). `"chào bạn"` được chào lại;
  `"chào bạn, giao dịch 906 là gì"` vẫn đi truy xuất bình thường.
- Mẫu và câu chào **cấu hình được lúc chạy** (`chat.smallTalkPattern`, `chat.smallTalkReply`).
  Mẫu sai cú pháp ⇒ log `warn` và quay về mẫu mặc định, không làm chết chat.
- Trả lời được đánh dấu `abstainReason=SMALL_TALK` để tách khỏi thống kê từ chối thật.

### 3.2 Bước 7 — mở rộng truy vấn

Ba cơ chế chồng lên nhau, sinh ra nhiều biến thể truy vấn:

1. **Viết lại** (`queryRewrite.enabled`) — dùng model rẻ, gộp ngữ cảnh hội thoại
   ("cái đó" → tên đầy đủ).
2. **Từ điển thuật ngữ** (`retrieval.glossaryEnabled`, bảng `rag_synonyms`) — bù khoảng cách
   giữa cách người dùng gọi và cách tài liệu viết. Đây là công cụ mạnh nhất cho từ nghiệp vụ.
   Ví dụ thật: người dùng hỏi *"điện 906"* trong khi tài liệu viết *"giao dịch 906"* — thêm
   một dòng `điện → giao dịch, mã điện, MT598` là hỏi kiểu nào cũng ra.
3. **HyDE** (`retrieval.hydeEnabled`, mặc định tắt) — sinh câu trả lời giả rồi đem đi tìm.
   Tốn thêm một lời gọi model mỗi câu hỏi, bù được khác biệt cách diễn đạt.

Từ điển nạp lại **mỗi 60 giây**, nên thêm thuật ngữ không cần khởi động lại.

### 3.3 Bước 8 — truy xuất lai

Hai nhánh chạy **song song**:

| Nhánh | Cơ chế | Bắt được gì |
|---|---|---|
| Vector | pgvector HNSW, cosine trên `vector(1536)` | Câu hỏi diễn đạt khác tài liệu |
| Full-text | Postgres `tsvector`, cấu hình `'vi'` (`simple` + `unaccent`) | Mã số, tên riêng, gõ không dấu |

Hợp nhất bằng **RRF** (Reciprocal Rank Fusion), không cộng điểm thô. Ba chi tiết dễ hỏng:

- Cột `tsv` do **trigger** `trg_rag_chunks_tsv` sinh, không phải code Java ghi.
- Full-text ghép các từ bằng **OR** (`TsQueryBuilder.orQuery`). Quay lại `plainto_tsquery`
  (AND) là nhánh này thành vô dụng.
- pgvector áp filter **sau** khi duyệt HNSW ⇒ khi lọc theo category/ACL phải over-fetch
  (`filter-overfetch-multiplier`) rồi cắt lại ở Java.

### 3.4 Bước 9–10 — rerank và cổng từ chối

Đây là chỗ quyết định "trả lời hay không", và là lỗi nghiêm trọng nhất của bản cũ:

> Phải phân biệt **"không có gì liên quan"** với **"bộ rerank bị lỗi"**.

- `RerankResult.reliable()` + rỗng ⇒ thật sự không liên quan ⇒ **từ chối trả lời**.
- `degraded` ⇒ bộ rerank hỏng ⇒ `RelevanceGate` **chuyển sang chấm bằng cosine**, không
  từ chối oan.

Các nhánh quyết định của `RelevanceGate`, kèm `abstainReason` xuất hiện trong `debug`:

| Điều kiện | reason | Thông điệp |
|---|---|---|
| Không có ứng viên nào | `NO_CANDIDATES` | notFound |
| Rerank tin cậy nhưng chọn 0 đoạn | `RERANK_FOUND_NOTHING_RELEVANT` | notFound |
| Điểm rerank cao nhất < `minRerankScore` | `RERANK_SCORE_BELOW_THRESHOLD` | notRelevant |
| Rerank hỏng, cosine < `minVectorScore` | `VECTOR_SCORE_BELOW_THRESHOLD` | notRelevant |
| Câu xã giao | `SMALL_TALK` | lời chào |

Cả hai thông điệp **cấu hình được** (`chat.notFoundMessage`, `chat.notRelevantMessage`).

### 3.5 Bước 11 — dựng prompt

- Tài liệu được bọc trong thẻ `<tai_lieu>…</tai_lieu>` và đi qua `PromptBuilder.neutralize`
  để **vô hiệu hoá prompt injection**: một tài liệu chứa `</tai_lieu>` hay
  `"bỏ qua hướng dẫn trên"` bị coi là **nội dung**, không phải mệnh lệnh.
- Trả lời bằng `parent_content` (đoạn cha, đủ ngữ cảnh) dù tìm bằng `content` (đoạn con,
  đủ sắc nét) — mô hình **parent–child**.
- **Persona của bot chèn TRƯỚC các quy tắc bắt buộc**, không phải sau: mô hình chịu ảnh
  hưởng mạnh nhất bởi phần cuối prompt, đặt persona ở cuối cho phép một dòng cấu hình vô
  hiệu hoá quy tắc "chỉ trả lời theo tài liệu".
- Từ bản này, **toàn bộ system prompt cấu hình được** qua `chat.systemPrompt`. Để trống
  nghĩa là *dùng bản mặc định trong jar*, **không phải** prompt rỗng — cố ý, vì prompt rỗng
  sẽ âm thầm gỡ bỏ quy tắc chỉ-theo-tài-liệu và ranh giới chống injection.

### 3.6 Bước 12 — hậu kiểm trích dẫn

`PromptBuilder.verifyCitations` rà số trích dẫn `[n]` trong câu trả lời: số vượt ngoài danh
sách nguồn thật bị **xoá khỏi câu trả lời** và ghi nhận. Mô hình bịa nguồn thì người đọc
không nhìn thấy nguồn bịa.

---

## 4. Luồng nạp tài liệu

```
File (PDF/DOCX/XLSX/PPTX/HTML/MD/TXT)
  → Quét virus (ClamAV, fail-closed)        ← đặt tại IngestionService.ingest
  → Chuyển đổi sang Markdown
       PDF   : PDFBox 3  (+ OCR nếu là bản scan)
       Office: Apache POI 5.4
       HTML  : jsoup + flexmark
  → Tách đoạn (MarkdownChunker / LegalStructureChunker)
  → Sinh ngữ cảnh cho từng đoạn (tuỳ chọn, tốn 1 lời gọi model mỗi đoạn)
  → Nhúng vector theo lô
  → Ghi rag_documents + rag_chunks   (trigger sinh cột tsv)
```

Ba đường nạp: **upload 1 file**, **upload hàng loạt**, **nạp cả thư mục trên máy chủ**
(`/admin/ingest-folder`, chỉ đọc trong `RAG_ALLOWED_ROOTS`). Tất cả chạy **nền** và có
`jobId` để theo dõi tiến độ, huỷ giữa chừng.

Điểm cần biết:

- **`doc_key` = `category/fileName`** là khoá ghi đè. Hai file trùng tên ở hai category khác
  nhau là **hai tài liệu khác nhau** — cố ý, để sửa lỗi ghi đè âm thầm của bản cũ.
- **Quét virus đặt ở `IngestionService.ingest`**, không ở controller: đó là điểm nghẽn duy
  nhất của cả ba đường nạp. `fail-closed=true` nghĩa là không kết nối được clamd ⇒ **từ chối**
  file.
- **PDF bản scan không nạp được nếu tắt OCR** — job tính là *thất bại* kèm cảnh báo, không im
  lặng bỏ qua. OCR tốn **một lời gọi model mỗi trang**, nên `rag.ocr.max-pages` là chặn thật.
- **`mergeTinySections` không được gộp qua ranh giới `Điều`** — một `Điều` ngắn bị gộp vào
  `Điều` kế tiếp sẽ mang nhãn sai và câu trả lời trích dẫn sai căn cứ mà vẫn đọc rất xuôi.
- Đổi `rag.chunking.*` ⇒ **phải nạp lại tài liệu**. Chỉ `rag.retrieval.*` mới áp dụng ngay.

---

## 5. Phân quyền — ba lớp

| Lớp | Cơ chế | Chặn ai |
|---|---|---|
| 1. Xác thực | API key `X-API-Key` (role ADMIN/USER) **hoặc** đăng nhập Entra (OIDC) | Người lạ |
| 2. Danh tính & nhóm | `EntraOidcUserService` → `GraphDirectoryClient.memberGroups` → `EntraScopeService` | Tài khoản ngoài `bsc.com.vn`, tài khoản khách |
| 3. **ACL tài liệu** | `rag_collections.acl_groups` ↔ nhóm Entra của người dùng → `AccessScope.departments` → điều kiện SQL trong `HybridRetriever` | Người trong công ty chưa được cấp quyền |

Lớp 3 mới là thứ thật sự bảo vệ dữ liệu — nó đúng kể cả khi bot bị cài sai chỗ.

Bốn quy tắc dễ làm sai:

1. **Không lấy được nhóm Entra = KHÔNG có quyền nào**, không phải "có mọi quyền".
   `memberGroups` trả rỗng khi Graph lỗi, và `EntraScopeService` hiểu rỗng là **đóng**.
   Đây là lý do secret hết hạn làm cả hệ thống đọc được 0 tài liệu mà không báo lỗi gì.
2. **`AccessScope.cacheScopeKey()` phải gồm cả phần role**, không chỉ phòng ban. Thiếu role
   ⇒ câu trả lời sinh cho ADMIN được phục vụ lại cho USER. Cache semantic làm rò rỉ này nặng
   hơn vì không cần câu hỏi giống hệt.
3. **Trong channel Teams phải hạ quyền ADMIN xuống USER** (`BotAccessResolver`). Chỉ thu hẹp
   danh sách collection là chưa đủ, vì `HybridRetriever` lọc `allowed_roles` bằng
   `isAdmin() ? Set.of() : roles()` — một quản trị viên hỏi trong channel sẽ kéo tài liệu hạn
   chế ra cho cả kênh.
4. **Rỗng có nghĩa ngược nhau ở hai chỗ, và đó là chủ ý:** ACL collection rỗng = *đóng*;
   đối tượng sử dụng bot rỗng = *mở*. Lý do: cấm dùng bot không bảo vệ dữ liệu — dữ liệu do
   ACL collection bảo vệ.

> **Bẫy thứ tự khi bật Entra:** chưa có collection nào gắn `aclGroups` thì
> `PlatformService.hasNoAcl()` trả `true`, hệ thống quay sang bảng
> `rag.entra.group-departments` (thường rỗng) ⇒ **mọi người không phải admin đọc được 0 tài
> liệu**. Triệu chứng giống hệt lỗi Graph.

> ⚠️ **Đường API key KHÔNG đi qua ba lớp trên.** `rag.security.clients[].departments=*`
> nghĩa là **bỏ lọc category hoàn toàn**, nên ai giữ `RAG_USER_API_KEY` đọc được cả kho bất
> kể nhóm Entra. Chỉ dùng key đó cho tích hợp máy–máy; người thật phải đi SSO. Muốn giới
> hạn thì khai `departments=cong-ty` thay cho `*`.

### 5.1 Uỷ quyền cho người ngoài đội quản trị

Hệ thống chỉ có hai vai trò (`ADMIN`, `USER`), nên trước đây muốn để một cán bộ nghiệp vụ tự
nạp tài liệu thì phải cho họ vào nhóm quản trị — tức toàn quyền trên cả hệ thống. Bảng
`rag_grants` sinh ra để giải quyết việc này nhưng **chỉ được ghi vào, không ai đọc ra**, nên
cấp quyền là một hành động vô hiệu trông như đã có tác dụng.

Nay `DelegationService` là chỗ **thực thi** bảng đó:

| Khái niệm | Giá trị |
|---|---|
| Đối tượng | `USER` (Object ID người dùng) hoặc `GROUP` (Object ID nhóm Entra) |
| Phạm vi | `COLLECTION` (một nhóm tài liệu) hoặc `BOT` |
| Vai trò | `OWNER` / `EDITOR` được sửa · `VIEWER` chỉ xem |

Người được uỷ quyền vào **`/rag/my.html`** và làm được đúng bốn việc, trong đúng phạm vi được
giao: nạp tài liệu, xoá tài liệu, chọn nhóm Entra được đọc, cấu hình bot + gán bot cho Team
của họ. Họ **không** thấy và **không** sửa được gì của phòng khác.

Bốn quyết định thiết kế:

- **Không nới `/admin/**` mà mở namespace riêng `/api/v1/rag/my/**`.** Mọi endpoint ở đó tự
  kiểm tra lại quyền. Nhờ vậy thêm một endpoint quản trị mới về sau không vô tình lọt sang
  đường uỷ quyền.
- **`category` lấy từ collection, không lấy từ request.** Cho người nạp tự khai `category` là
  cho họ ghi vào nhóm của phòng khác.
- **Chống leo thang quyền:** một OWNER chỉ cấp quyền đọc được cho **nhóm mà chính họ là thành
  viên**. Không có luật này, họ chọn nhóm toàn công ty và công khai cả kho bằng một cú click.
  Nhóm rộng quá thì chặn cứng bằng `rag.grants.acl-denied-groups` — danh sách này **ràng buộc
  cả ADMIN**, để không mở toang do nhầm tay.
- **Gán bot cho Team phải là Team của mình.** `aadGroupId` của một Team chính là Object ID một
  nhóm Entra, nên đòi người gán là thành viên nhóm đó là một phép kiểm tra thật.

Admin cấp quyền **bằng email** — `POST /admin/grants` với `principalUpn`, Graph tự đổi sang
Object ID (bảng lưu `UUID`). Gõ GUID bằng tay là chỗ hay sai nhất nên không bắt buộc nữa.
Xem `DelegationServiceTest` cho các đường leo thang quyền đã chặn.

---

## 6. Nền tảng nhiều bot

Một cài đặt phục vụ nhiều bot Teams khác nhau, mỗi bot có persona, model và phạm vi tài liệu
riêng.

| Khái niệm | Bảng | Ghi chú |
|---|---|---|
| Nhóm tài liệu | `rag_collections` | **`slug` chính là cột `category`** của tài liệu |
| ACL nhóm | `rag_collection_acl` | Object ID nhóm Entra |
| Bot | `rag_bots` | persona, provider, model, bot mặc định |
| Bot ↔ nhóm tài liệu | `rag_bot_collections` | Bot chỉ đọc được nhóm đã gán |
| Kênh Teams | `rag_bot_channels` | Định tuyến theo Team/channel |
| Đối tượng dùng bot | `rag_bot_audience` | Rỗng = mở cho mọi người |

Hai quyết định thiết kế:

- **V3 cố ý không thêm khoá ngoại `collection_id` vào `rag_chunks`** để không phải sửa một
  dòng SQL tìm kiếm nào. Đổi lại, mọi đường ghi `category` **phải đi qua `PlatformService`**,
  vì DB không còn ràng buộc giúp.
- **Bot ghi vào `rag_messages.bot_slug` bằng SLUG, không phải khoá ngoại.** Xoá một bot không
  được làm mất số liệu lịch sử của nó. Slug nằm ở mức *tin nhắn* để câu báo cáo chỉ đọc một
  bảng, và để một hội thoại đổi bot không bị quy toàn bộ lịch sử cho bot mới.

---

## 7. Cấu hình

Ba tầng, ưu tiên từ dưới lên:

```
1. application.properties trong jar          giá trị mặc định
2. /app/aiagent/config/application.properties bản đồ khoá=${BIEN}, KHÔNG chứa giá trị
3. /app/aiagent/config/aiagent.env            GIÁ TRỊ + bí mật, chmod 600
4. Bảng rag_settings                          đổi lúc chạy, KHÔNG cần khởi động lại
```

### 7.1 Đổi được lúc chạy — `POST /api/v1/rag/settings`

Áp dụng ngay, lưu vào DB, nạp lại khi khởi động:

| Nhóm | Khoá |
|---|---|
| Model | `llm.provider`, `llm.model`, `internal.provider`, `internal.model` |
| Truy xuất | `retrieval.topK`, `.candidates`, `.vectorTopK`, `.fulltextTopK`, `.hybridEnabled`, `.multiQueryEnabled`, `.hydeEnabled`, `.recencyBoostEnabled`, `.excludeExpired`, `.glossaryEnabled`, `.clarifyAmbiguousEnabled` |
| Ngưỡng từ chối | `retrieval.minRerankScore`, `.minVectorScore`, `.abstainWhenBelowThreshold` |
| Rerank | `rerank.provider` (LLM / COHERE / NONE) |
| Cache | `cache.enabled`, `.semanticEnabled`, `.semanticThreshold` |
| Nạp liệu | `ingestion.contextualEnabled` |
| Viết lại | `queryRewrite.enabled` |
| **Lời văn & prompt** | `chat.citationsEnabled`, **`chat.systemPrompt`**, **`chat.notFoundMessage`**, **`chat.notRelevantMessage`**, **`chat.smallTalkEnabled`**, **`chat.smallTalkPattern`**, **`chat.smallTalkReply`** |

Năm khoá dạng văn bản để **trống nghĩa là dùng bản mặc định trong jar**, không phải xoá
trắng. `GET /settings` trả kèm khối `defaults` để màn hình quản trị hiện đúng cái mà giá trị
rỗng sẽ rơi về. Mẫu regex sai cú pháp bị **từ chối ngay lúc lưu**, không đợi tới lúc dùng.

### 7.2 Phải khởi động lại

`SERVER_*`, `DB_*`, `ENTRA_*`, `BOT_*`, `RAG_EMBEDDING_*`, `RAG_ALLOWED_ROOTS`, `RAG_OCR_*`,
`RAG_AV_*`.

### 7.3 Đổi là hỏng dữ liệu

**Vector câu hỏi và vector tài liệu phải cùng một model.** Đổi
`rag.embedding.provider`/`dimensions` ⇒ tạo lại schema và **nạp lại toàn bộ**. Số chiều đi
thẳng vào DDL của Flyway qua placeholder `${embeddingDim}`; `SchemaValidator` **chặn khởi
động** nếu cấu hình lệch với CSDL. Và phải **xoá `rag_answer_cache`** vì cache giữ vector câu
hỏi theo model cũ.

---

## 8. Bản đồ API

Mọi đường dẫn dưới đây nằm sau tiền tố `/rag` trên UAT.
Ví dụ: `https://chatbot-uat.bsc.com.vn/rag/api/v1/rag/chat`.

### Hỏi–đáp
| Method | Đường dẫn | Việc |
|---|---|---|
| POST | `/api/v1/rag/chat` | Hỏi, trả về câu trả lời + trích dẫn + `debug` |
| POST | `/api/v1/rag/chat/stream` | Như trên, trả theo dòng (SSE) |
| GET/DELETE | `/api/v1/rag/conversations[/{id}]` | Lịch sử hội thoại |
| POST | `/api/v1/rag/feedback` | 👍/👎 cho một câu trả lời |
| GET | `/api/v1/rag/models` | Danh mục model khả dụng |
| GET | `/api/v1/rag/me` | Danh tính, quyền, nhóm Entra, phòng ban |

### Nạp và quản lý tài liệu
| Method | Đường dẫn | Việc |
|---|---|---|
| POST | `/admin/upload`, `/admin/upload-batch` | Nạp file |
| POST | `/admin/ingest-folder` | Nạp cả thư mục trên máy chủ |
| POST | `/admin/convert` | Chỉ chuyển sang Markdown, không nạp |
| GET/POST | `/admin/jobs[/{id}][/cancel]` | Theo dõi / huỷ job nạp |
| GET/DELETE | `/admin/documents[/{id}][/markdown]` | Danh sách, xem, xoá tài liệu |
| GET | `/admin/stats`, `/admin/categories` | Số liệu kho |

### Nền tảng bot và phân quyền
| Method | Đường dẫn | Việc |
|---|---|---|
| GET | `/admin/platform` | Ảnh chụp toàn bộ cấu hình nền tảng |
| CRUD | `/admin/collections[/{id}][/acl]` | Nhóm tài liệu + ACL nhóm Entra |
| CRUD | `/admin/bots[/{id}][/default][/collections][/audience]` | Bot |
| POST/DELETE | `/admin/bot-channels`, `/admin/grants` | Kênh Teams, cấp quyền lẻ |

### Chất lượng và chẩn đoán
| Method | Đường dẫn | Việc |
|---|---|---|
| POST | `/admin/retrieval-test` | **Mổ xẻ một câu hỏi**: biến thể truy vấn, kết quả từng nhánh, điểm RRF, điểm rerank, quyết định của cổng từ chối — không sinh câu trả lời |
| POST | `/eval/retrieval` | **recall@k + MRR**, rẻ và deterministic |
| POST | `/eval/run` | faithfulness / answer-relevance, tốn 1 lời gọi model giám khảo mỗi case |
| POST | `/eval/cases/generate` | Sinh bộ câu hỏi chuẩn **từ chính kho tài liệu** |
| POST | `/eval/cases/harvest` | Thu hoạch câu hỏi thật từ `rag_messages` |
| GET | `/eval/runs[/{id}]` | Kết quả từng lần đo, kèm tham số lúc chạy |
| GET/POST | `/admin/embedding-trial/*` | Thử model embedding mới trên bảng phụ trước khi đổi thật |

### Vận hành
| Method | Đường dẫn | Việc |
|---|---|---|
| GET | `/admin/overview`, `/admin/metrics` | Bảng điều khiển |
| GET | `/admin/reports/{bots,daily,gaps}` | Báo cáo theo bot, theo ngày, câu hỏi không trả lời được |
| GET | `/admin/audit`, `/admin/audit/summary` | Nhật ký kiểm toán (**chỉ đọc**) |
| GET/DELETE | `/admin/cache`, `/admin/conversations/purge` | Cache, dọn hội thoại |
| GET/POST | `/admin/retention[/run]` | Vòng đời dữ liệu |
| GET/POST | `/settings`, `/settings/models` | Cấu hình lúc chạy |
| GET/POST | `/admin/providers/*` | Khoá API của từng nhà cung cấp |
| POST | `/api/messages` | **Endpoint bot Teams** (Bot Framework gọi vào) |
| GET | `/actuator/health`, `/actuator/prometheus` | Sức khoẻ, số liệu |

---

## 9. Mô hình dữ liệu

23 bảng, quản lý bằng Flyway V1→V7.

| Nhóm | Bảng |
|---|---|
| Tài liệu | `rag_documents`, `rag_chunks` (vector + tsv + parent_content) |
| Hội thoại | `rag_conversations`, `rag_messages`, `rag_message_citations`, `rag_feedback` |
| Cache | `rag_answer_cache` (giữ cả vector câu hỏi) |
| Nền tảng | `rag_collections`, `rag_collection_acl`, `rag_bots`, `rag_bot_collections`, `rag_bot_channels`, `rag_bot_audience`, `rag_grants` |
| Người dùng | `rag_users` |
| Chất lượng | `rag_eval_cases`, `rag_eval_runs`, `rag_eval_results` |
| Vận hành | `rag_ingest_jobs`, `rag_settings`, `rag_audit_log`, `rag_synonyms` |

| Migration | Nội dung |
|---|---|
| V1 | Schema lõi (placeholder `${embeddingDim}`) |
| V2 | Người dùng Entra |
| V3 | Nền tảng nhiều bot |
| V4 | Chất lượng tìm kiếm + từ điển thuật ngữ |
| V5 | Đánh giá truy xuất |
| V6 | Thống kê sử dụng theo bot |
| V7 | Nhật ký kiểm toán |

---

## 10. Quan sát, kiểm toán, vòng đời

**Nhật ký kiểm toán ghi bằng FILTER, không bằng lời gọi trong từng controller.** `AuditFilter`
nằm ngay trước `ExceptionTranslationFilter` nên bắt được cả thao tác **bị từ chối** (401/403)
— đó mới là thứ cần nhất khi điều tra. Thêm endpoint quản trị mới thì **không phải làm gì**.
Nhật ký chỉ có đường đọc, không có đường xoá qua API.

**Số liệu** (`RagMetrics` → `/actuator/prometheus`): số câu hỏi, độ trễ theo bước
(retrieval / rerank / generation / total), tỷ lệ cache hit, tỷ lệ từ chối, lỗi — tất cả gắn
nhãn `bot`.

> **Mọi chuỗi cùng một tên metric phải có CÙNG bộ khoá tag.** Prometheus loại bỏ **im lặng**
> chuỗi lệch tag — không một dòng log nào. Đã xảy ra thật với `rag.latency`. Nhãn bot **không
> bao giờ để rỗng**; đường web mang nhãn `"web"`.

**Vòng đời dữ liệu** (`RetentionService`, `@Scheduled`): dọn hội thoại, nhật ký và job quá
hạn theo `rag.retention.*`.

**Sao lưu**: `deploy/backup.sh` chạy 02:15 hằng ngày qua cron, `pg_dump` từ container, tự
kiểm tra mục lục bản dump và xoá bản cũ hơn 14 ngày.

**Quyền riêng tư**: `RAG_LOG_QUESTIONS` / `RAG_LOG_ANSWERS` mặc định `false` — câu hỏi và câu
trả lời **không** vào file log (vẫn lưu trong DB để làm báo cáo và thu hoạch eval).

---

## 11. Trạng thái thực tế trên UAT

Kiểm chứng ngày 19/08/2026:

| Hạng mục | Trạng thái |
|---|---|
| `aiagent.service` / `rag-postgres.service` / `httpd` | ✅ `active`, `enabled` |
| Schema | ✅ Flyway V1→V7, `vector(1536) khop cau hinh` |
| Embedding | ✅ OpenAI `text-embedding-3-small`, 1536 chiều |
| Tiền tố `/rag` | ✅ health `200` cả trực tiếp lẫn qua Apache |
| Drupal | ✅ không ảnh hưởng (`/` → 302, `/user/login` → 200, cron → 204) |
| Đăng nhập Entra | ✅ đã bật, `redirect_uri` sinh ra khớp Portal |
| Graph client-credentials | ✅ token OK, `User.Read.All` 200, `GroupMember.Read.All` 200 (15 nhóm) |
| Sao lưu | ✅ cron 02:15, đã có bản dump hợp lệ |
| Kho tài liệu | 1 tài liệu / 178 đoạn (`Tai_lieu_phan_tich_yeu_cau_He_thong_Carbon_STP_VSD_v1.docx`) |
| Từ điển thuật ngữ | 26 mục (14 từ migration + 12 mục nghiệp vụ các-bon) |
| Bot Teams | ⛔ **chưa bật** — chưa tạo Azure Bot, chưa có icon Teams app |
| Collection + ACL | ⛔ **chưa tạo** — nên hiện chỉ admin đọc được tài liệu |
| Eval baseline | ⛔ **chưa đo** |

### Việc còn lại để hoàn chỉnh

1. Gỡ `ENTRA_BOOTSTRAP_ADMINS` sau khi xác nhận đăng nhập bằng nhóm Entra.
2. Tạo collection cho từng phòng ban + gắn Object ID nhóm Entra.
3. Nạp tài liệu thật, đo baseline bằng `/eval/cases/generate` rồi `/eval/retrieval`.
4. Tạo Azure Bot, bổ sung `color.png` / `outline.png`, đóng gói và tải Teams app lên.
5. Đổi client secret của Entra (đã đi qua kênh chat) và đặt lịch nhắc hạn 18/08/2028.

---

## 12. Khi câu trả lời chưa tốt — trình tự xử lý

Rút ra từ ca thật trong ngày: người dùng hỏi *"Điện 906 là điện gì"* và bị trả lời "không tìm
thấy", trong khi *"Giao dịch 906 là giao dịch gì"* lại ra đúng.

**Bước 1 — mổ xẻ, đừng đoán.** `POST /admin/retrieval-test` trả về từng chặng. Câu hỏi cần
trả lời trước tiên: **đoạn đúng có nằm trong danh sách ứng viên không?**

| Kết quả | Nghĩa là | Xử lý |
|---|---|---|
| Đoạn đúng **không** có trong ứng viên | Hỏng ở **truy xuất** | Từ điển thuật ngữ, bật HyDE, tăng `candidates`, xem lại cách tách đoạn |
| Có trong ứng viên nhưng **rerank loại đi** | Hỏng ở **từ vựng / rerank** | Từ điển thuật ngữ, tăng `topK`, hạ `minRerankScore` |
| Được chọn nhưng câu trả lời vẫn sai | Hỏng ở **sinh câu trả lời** | Sửa `chat.systemPrompt`, đổi model |

Ca "điện 906" rơi vào hàng thứ hai: đoạn 906 **có** trong 35 ứng viên nhưng bộ rerank chỉ giữ
2 đoạn và loại nó. Thêm một dòng từ điển `điện → giao dịch, mã điện, MT598` là xong — biến
thể truy vấn trở thành *"Điện 906 là giao dịch gì?"* và đoạn đúng lên hạng 1.

**Bước 2 — các cần gạt, theo thứ tự rẻ đến đắt:**

| Cần gạt | Chi phí | Hợp với |
|---|---|---|
| Thêm từ điển thuật ngữ | Miễn phí, hiệu lực sau 60 giây | Từ nghiệp vụ, viết tắt, cách gọi nội bộ |
| Tăng `retrieval.topK` / `candidates` | Không đáng kể | Câu hỏi liệt kê, tổng hợp nhiều mục |
| Hạ `minRerankScore` | Không đáng kể | Từ chối quá nhiều |
| Tắt `clarifyAmbiguousEnabled` | Không đáng kể | Hệ thống hỏi lại lung tung thay vì trả lời |
| Bật `hydeEnabled` | +1 lời gọi model mỗi câu hỏi | Cách diễn đạt lệch nhiều so với tài liệu |
| Bật `ingestion.contextualEnabled` + **nạp lại** | +1 lời gọi model mỗi đoạn, một lần | Tài liệu nhiều bảng, nhiều mục đánh số |
| Sửa `chat.systemPrompt` | Miễn phí | Giọng văn, độ dài, cách trình bày |

**Bước 3 — đo, đừng cảm tính.** `POST /eval/retrieval` cho recall@k và MRR, rẻ và
deterministic; chạy sau *mỗi* lần đổi tham số truy xuất. `includeRerank=true` cho biết bộ
rerank đang làm tốt lên hay **làm hỏng** thứ tự.

### Điểm yếu đã biết

- **Câu hỏi đếm / liệt kê toàn văn bản** ("carbon gồm bao nhiêu điện") vẫn yếu: RAG theo đoạn
  chỉ nhìn thấy `topK` đoạn, không nhìn thấy cả tài liệu. Cách xử lý là bảo đảm bảng tổng hợp
  nằm gọn trong một đoạn và tăng `topK`.
- **Bước hỏi lại khi câu hỏi mơ hồ** dễ hiểu nhầm từ nghiệp vụ (đã tắt trên UAT).
- **Số hiệu ngắn** (901–912) là token yếu với embedding; nhánh full-text mới là thứ bắt được
  chúng — thêm một lý do không được để nhánh này hỏng.
