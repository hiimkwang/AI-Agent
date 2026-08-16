# Nền tảng Bot nội bộ BSC — thiết kế & lộ trình

Tài liệu này thiết kế đường đi từ hệ thống RAG hiện tại (một service, một webhook Teams,
xác thực bằng API key) thành **nền tảng nhiều bot** tích hợp Teams, đăng nhập bằng tài khoản
`@bsc.com.vn`, phân quyền theo Entra ID.

Bốn yêu cầu gốc:

1. Chatbot trong Teams, tra cứu tài liệu nội bộ + trợ giúp cán bộ.
2. Tạo được nhiều bot: theo phòng ban, hoặc cho toàn công ty.
3. Đăng nhập `x@bsc.com.vn` vào giao diện quản trị để upload tài liệu / tạo bot / gắn vào Teams.
4. Nâng cấp chất lượng trả lời.

Đọc kèm: [ARCHITECTURE.md](ARCHITECTURE.md) (kiến trúc hiện tại), [../CLAUDE.md](../CLAUDE.md)
(các bất biến không được phá).

---

## 0. Hiện trạng — cái gì dùng được, cái gì phải thay

### Dùng lại được (phần lõi đã tốt)

| Thành phần | Trạng thái |
|---|---|
| Pipeline RAG (`RagChatService`) | Tốt: cache → history → multi-query → hybrid + RRF → rerank → cổng từ chối → prompt → stream |
| `HybridRetriever` | Tốt: 2 nhánh song song, thất bại độc lập, giữ điểm cosine gốc để đặt ngưỡng |
| ACL ở tầng SQL (`ChunkRepository.appendConditions`) | Đúng hướng: đã lọc theo `category` + `allowed_roles` ngay trong SQL, có over-fetch cho pgvector |
| `AccessScope.narrowTo` | Đúng hướng: không tin `category` do client khai |
| Ingest, chunking, eval, settings runtime | Dùng lại toàn bộ |

Nói cách khác: **lõi RAG không phải viết lại.** Việc cần làm là thay tầng *danh tính* và
tầng *định tuyến bot* bên trên nó.

### Phải thay

**a) Outgoing Webhook Teams không đáp ứng được yêu cầu 1–3.**

`TeamsWebhookController` hiện dùng cơ chế Outgoing Webhook. Giới hạn cứng của cơ chế này:

- Chỉ hoạt động **trong đúng team đã tạo webhook**, và **chỉ khi bị @mention trong channel**.
  Không có chat riêng 1:1, không có group chat.
- **Không có danh tính người dùng.** Payload chỉ có `from.id` (id nội bộ Teams), không có
  `aadObjectId`, không có email → không thể phân quyền theo người.
- Không gửi được tin nhắn chủ động (thông báo, nhắc việc), không typing indicator,
  không Adaptive Card, không cập nhật tin nhắn dần.
- Không phát hành được như một app cho toàn tổ chức — mỗi team owner phải tự tạo webhook.

Hệ quả trực tiếp đang có trong code, [TeamsWebhookController.java:88](../src/main/java/com/ai/aiagent/chat/TeamsWebhookController.java#L88):

```java
AccessScope scope = new AccessScope("teams", Set.of("USER"), Set.of(), true);
//                                                                    ^^^^ allDepartments = true
```

Mọi người dùng Teams đang có quyền đọc **tài liệu của tất cả phòng ban**. Hiện tại chưa
gây rò rỉ vì các API key khác cũng đang `departments=*`, nhưng đây là lỗ đầu tiên phải bịt
khi bắt đầu phân quyền thật.

**b) Cache câu trả lời sẽ rò rỉ khi có ACL.**

`AccessScope.cacheScopeKey()` trả `"all"` khi `allDepartments = true`
([AccessScope.java:49](../src/main/java/com/ai/aiagent/security/AccessScope.java#L49)).
`AnswerCacheService` dùng chuỗi này làm phần khoá cache. Khi thêm ACL theo người, khoá cache
**bắt buộc** phải chứa tập collection người đó đọc được + bot đang dùng, nếu không câu trả lời
sinh cho cán bộ Nhân sự sẽ được phục vụ lại cho người khác. Cache semantic (cosine ≥ 0.97)
làm rủi ro này nặng hơn vì không cần câu hỏi giống hệt.

**c) API key không thể đại diện cho người.**

`SecurityProperties.Client` = 1 key + roles + departments, cấu hình trong
`application.properties`. Mô hình này đúng cho service-to-service, nhưng không mô tả được
"anh A phòng Kế toán". Cần thêm một tầng danh tính người dùng (Entra), giữ API key cho
máy gọi máy.

**d) `/admin.html` đang `permitAll`.**

[SecurityConfig.java:57](../src/main/java/com/ai/aiagent/security/SecurityConfig.java#L57) mở
trang HTML admin cho mọi người (các API bên dưới vẫn được bảo vệ). Khi có SSO thì trang này
phải `authenticated`.

---

## 1. Chọn đường tích hợp Teams

Ba lựa chọn thật, không phải ba biến thể của một lựa chọn:

### Phương án A — Custom bot qua Azure Bot Service ✅ **khuyến nghị**

Đăng ký một **Azure Bot** resource, trỏ messaging endpoint về service của mình
(`POST /api/messages`), đóng gói thành **Teams app** (file manifest + icon, zip) rồi
publish vào app catalog của tổ chức qua Teams Admin Center.

- ✔ Toàn quyền: chat riêng, channel, group chat, Adaptive Card, tin nhắn chủ động, typing.
- ✔ Có `from.aadObjectId` + `tenantId` → phân quyền theo người thật.
- ✔ Giữ nguyên lõi RAG đã đầu tư.
- ✔ Không phát sinh license/người dùng.
- ✘ Phải tự xử lý giao thức Bot Framework (xác thực JWT vào, lấy token gọi ra).
- ✘ Cần endpoint HTTPS công khai mà Azure Bot Service gọi được.

### Phương án B — Copilot Studio agent gọi API RAG của mình

Tạo agent trong Copilot Studio, khai báo service hiện tại như một custom connector / plugin,
Copilot Studio lo phần Teams.

- ✔ Ít code nhất, quản trị qua UI, Microsoft lo phần Teams + danh tính.
- ✘ **Chi phí license** (Copilot Studio messages / M365 Copilot seat) nhân theo số người dùng.
- ✘ Mất quyền điều khiển pipeline: orchestration, rerank, cổng từ chối, prompt do Microsoft
  quyết. Toàn bộ phần đã xây trong `RagChatService` bị bỏ hoặc bị bọc lại một cách vụng.
- ✘ Dữ liệu câu hỏi đi qua dịch vụ Microsoft — cần đánh giá với bộ phận tuân thủ.

Dùng phương án này nếu mục tiêu là *lên nhanh nhất có thể* và chấp nhận chất lượng do
Microsoft quyết. Với công ty chứng khoán đã tự xây RAG có eval, tôi không khuyến nghị.

### Phương án C — Giữ Outgoing Webhook

Không đáp ứng yêu cầu 2 và 3. Chỉ dùng như bản demo tạm.

> **Chốt: Phương án A.** Phần còn lại của tài liệu này giả định A.

### Ghi chú kỹ thuật cho phương án A

**SDK hay tự cài giao thức?**
`com.microsoft.bot:bot-integration-spring` (botbuilder-java) có trên Maven Central nhưng
nhịp cập nhật rất chậm; Microsoft đã chuyển hướng sang *Microsoft 365 Agents SDK*, và
hỗ trợ Java của SDK mới cần kiểm tra lại tại thời điểm triển khai. **Xác minh trước khi
phụ thuộc.**

Giao thức Bot Framework thực chất rất mỏng, tự cài an toàn hơn phụ thuộc SDK ngưng phát triển
— và repo này đã có tiền lệ (`GeminiLlmClient` gọi REST tay vì LangChain4j 0.31 chưa có module).
Hai phần cần làm:

*Chiều vào* — `POST /api/messages`, header `Authorization: Bearer <JWT>`. Phải kiểm tra:

| Kiểm tra | Giá trị |
|---|---|
| Signature | JWKS tại `https://login.botframework.com/v1/.well-known/openidconfiguration` |
| `iss` | `https://api.botframework.com` |
| `aud` | `MicrosoftAppId` của bot |
| `serviceUrl` claim | khớp `activity.serviceUrl` trong body |
| clock skew | ≤ 5 phút (đã có `TeamsProperties.maxClockSkewSeconds`) |

Spring Security `oauth2-resource-server` làm được phần JWKS; hai kiểm tra cuối viết thành
`OAuth2TokenValidator` riêng.

*Chiều ra* — lấy access token:

```
POST https://login.microsoftonline.com/{tenantId}/oauth2/v2.0/token
grant_type=client_credentials
scope=https://api.botframework.com/.default
```

(app SingleTenant; nếu MultiTenant thì tenant là `botframework.com`). Rồi
`POST {serviceUrl}/v3/conversations/{conversationId}/activities` để trả lời.

**Trả 200 ngay, xử lý bất đồng bộ.** Bot Framework có timeout ngắn. Luồng đúng:

```
nhận activity → validate JWT → 200 OK ngay
                             └─→ (thread pool) gửi typing
                                              → chạy RagChatService.answer()
                                              → gửi Adaptive Card kèm nguồn
```

Không được chạy pipeline RAG (8–20 giây) trong request gốc.

**Xác thực người dùng:** dùng `activity.from.aadObjectId` + `activity.conversation.tenantId`.
Không cần Teams SSO / OAuth prompt ở giai đoạn đầu — chỉ cần khi muốn bot **thao tác thay
người dùng** (đọc mail, ghi SharePoint của họ). Nhóm Entra của người dùng lấy qua Graph
app-only, có cache (mục 3).

---

## 2. Nhiều bot — mô hình dữ liệu và định tuyến

### 2.1 Hai cách tạo "nhiều bot"

**Cách 1 — một Azure Bot, N bot logic (khuyến nghị làm nền).**

Một app registration duy nhất, một Teams app duy nhất tên "BSC Assistant". Bot logic nào
được dùng thì suy ra từ **nơi tin nhắn đến**:

| Ngữ cảnh | Định tuyến |
|---|---|
| Channel trong Team của phòng ban | `channelData.team.aadGroupId` → tra `rag_bot_channels` |
| Chat riêng 1:1 | bot mặc định toàn công ty; người dùng đổi bằng lệnh `/tro-ly nhan-su` hoặc Adaptive Card chọn |
| Group chat | bot mặc định, giới hạn ở collection công khai |

Thêm bot mới cho phòng ban = **thêm 1 dòng DB + cài app vào Team của phòng đó**.
Không cần IT tạo app registration, không cần approve app mới.

**Cách 2 — N Azure Bot, N Teams app.**

Mỗi bot có tên, avatar, app id riêng; định tuyến bằng `activity.recipient.id`.

- ✔ Bot thật sự riêng biệt, thương hiệu riêng.
- ✔ Chặn được ở tầng Teams: **App permission policy** trong Teams Admin Center quyết định
  ai được cài / thấy app đó.
- ✘ N app registration + N secret/cert phải gia hạn + N manifest + N lần approve.

**Khuyến nghị: xây code hỗ trợ cả hai từ đầu**, chạy Cách 1 làm mặc định, dùng Cách 2 cho
1–2 bot cần thương hiệu riêng (ví dụ bot dành cho toàn công ty, hoặc bot Pháp chế cần
kiểm soát cài đặt ở tầng Teams). Bảng `rag_bots` có cả `teams_app_id` (Cách 2) và
`rag_bot_channels` (Cách 1) nên một hàm định tuyến phục vụ được cả hai.

### 2.2 Schema mới (Flyway `V2__bot_platform.sql`)

`category` hiện tại được nâng thành **collection** — một thực thể có tên, chủ sở hữu, ACL.

> **Đã triển khai khác một điểm so với thiết kế dưới đây, và đó là chủ ý.**
> Bản thi hành (`V3__bot_platform.sql`) giữ `collection.slug` **chính là** cột `category`
> sẵn có, thay vì thêm khoá ngoại `collection_id` vào `rag_chunks`. Lý do: toàn bộ SQL
> tìm kiếm (vector + full-text + over-fetch cho pgvector) đã được chỉnh kỹ, đổi điều kiện
> lọc ở đó là đặt cược chất lượng truy xuất vào một việc thuần hành chính. Đổi lại chỉ mất
> một ràng buộc toàn vẹn ở tầng DB, bù bằng `UNIQUE` trên slug và bằng việc mọi đường ghi
> `category` đều đi qua `PlatformService`. Bất biến `doc_key = category/fileName` giữ
> nguyên. Schema dưới đây là bản thiết kế gốc, giữ lại để đối chiếu.

```sql
-- Tập tài liệu: đơn vị của ACL và của ingest
CREATE TABLE rag_collections (
    id              BIGSERIAL PRIMARY KEY,
    slug            TEXT NOT NULL UNIQUE,      -- thay cho category cũ
    name            TEXT NOT NULL,
    description     TEXT,
    -- Tài liệu mật chỉ được trả lời trong chat riêng, KHÔNG trả lời trong channel.
    -- Xem mục 4.4 về lý do.
    channel_allowed BOOLEAN NOT NULL DEFAULT true,
    created_by      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Ai đọc được collection: theo NHÓM ENTRA (nguồn sự thật do IT/HR duy trì)
CREATE TABLE rag_collection_acl (
    collection_id   BIGINT NOT NULL REFERENCES rag_collections(id) ON DELETE CASCADE,
    entra_group_id  UUID NOT NULL,
    group_name      TEXT,                      -- chỉ để hiển thị, không dùng để phân quyền
    PRIMARY KEY (collection_id, entra_group_id)
);

-- Bot: persona + model + tham số retrieval riêng
CREATE TABLE rag_bots (
    id                  BIGSERIAL PRIMARY KEY,
    slug                TEXT NOT NULL UNIQUE,
    display_name        TEXT NOT NULL,
    description         TEXT,
    teams_app_id        TEXT,                  -- Cách 2: NULL nếu dùng bot chung
    persona_prompt      TEXT,                  -- thêm vào system prompt
    greeting            TEXT,                  -- card chào khi cài / lệnh /help
    llm_provider        TEXT,
    llm_model           TEXT,
    retrieval_overrides JSONB,                 -- top-k, ngưỡng... đè RagProperties
    -- false = chỉ trả lời theo tài liệu (mặc định). true = cho phép trả lời bằng
    -- kiến thức chung khi không tìm thấy tài liệu, có cảnh báo rõ.
    allow_general_knowledge BOOLEAN NOT NULL DEFAULT false,
    status              TEXT NOT NULL DEFAULT 'ACTIVE',
    created_by          TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Bot đọc những collection nào
CREATE TABLE rag_bot_collections (
    bot_id        BIGINT NOT NULL REFERENCES rag_bots(id) ON DELETE CASCADE,
    collection_id BIGINT NOT NULL REFERENCES rag_collections(id) ON DELETE CASCADE,
    PRIMARY KEY (bot_id, collection_id)
);

-- Ai được DÙNG bot (khác với ai được ĐỌC collection)
CREATE TABLE rag_bot_audience (
    bot_id         BIGINT NOT NULL REFERENCES rag_bots(id) ON DELETE CASCADE,
    principal_type TEXT NOT NULL,              -- GROUP | USER
    principal_id   UUID NOT NULL,              -- Entra objectId
    display_name   TEXT,
    PRIMARY KEY (bot_id, principal_type, principal_id)
);

-- Cách 1: bot nào phục vụ Team/channel nào
CREATE TABLE rag_bot_channels (
    id                 BIGSERIAL PRIMARY KEY,
    bot_id             BIGINT NOT NULL REFERENCES rag_bots(id) ON DELETE CASCADE,
    tenant_id          UUID NOT NULL,
    team_aad_group_id  UUID,                   -- NULL = áp dụng cho mọi team
    channel_id         TEXT,                   -- NULL = mọi channel của team
    UNIQUE (tenant_id, team_aad_group_id, channel_id)
);

-- Người dùng + cache nhóm Entra (tránh gọi Graph mỗi tin nhắn)
CREATE TABLE rag_users (
    entra_object_id  UUID PRIMARY KEY,
    upn              TEXT NOT NULL,
    display_name     TEXT,
    department       TEXT,
    job_title        TEXT,
    group_ids        JSONB,                    -- mảng UUID, nhóm transitive
    groups_synced_at TIMESTAMPTZ,
    last_seen_at     TIMESTAMPTZ
);

-- Ai quản trị bot / collection nào (phần MỊN, giữ trong app, không nhờ Entra)
CREATE TABLE rag_grants (
    id             BIGSERIAL PRIMARY KEY,
    principal_type TEXT NOT NULL,              -- GROUP | USER
    principal_id   UUID NOT NULL,
    scope_type     TEXT NOT NULL,              -- BOT | COLLECTION
    scope_id       BIGINT NOT NULL,
    role           TEXT NOT NULL,              -- OWNER | EDITOR | VIEWER
    granted_by     TEXT,
    granted_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (principal_type, principal_id, scope_type, scope_id, role)
);

-- Tài liệu gắn vào collection (bổ sung, giữ cột category để tương thích ngược)
ALTER TABLE rag_documents ADD COLUMN collection_id BIGINT REFERENCES rag_collections(id);
ALTER TABLE rag_chunks    ADD COLUMN collection_id BIGINT;
CREATE INDEX idx_rag_chunks_collection ON rag_chunks (collection_id);

-- Truy vết: câu hỏi thuộc bot nào (cho báo cáo chi phí / chất lượng theo bot)
ALTER TABLE rag_conversations ADD COLUMN bot_id BIGINT REFERENCES rag_bots(id);
ALTER TABLE rag_conversations ADD COLUMN entra_object_id UUID;
```

Di trú dữ liệu: mỗi `category` phân biệt trong `rag_documents` → một dòng `rag_collections`,
rồi `UPDATE ... SET collection_id = ...`. Không phải nạp lại tài liệu (vector không đổi).

### 2.3 `AccessScope` mở rộng

Điểm mấu chốt: **không viết đường tắt vòng qua `AccessScope`.** Mọi thứ vẫn đi qua record này
để `ChunkRepository` không phải biết gì về Teams hay Entra.

```java
public record AccessScope(
        String principalId,          // Entra objectId, hoặc id của API key
        String upn,                  // a@bsc.com.vn — chỉ để log/audit
        Set<String> roles,           // ADMIN | USER (thô, từ app role của Entra)
        Set<UUID> entraGroups,       // nhóm transitive, đã cache
        Set<Long> readableCollections,  // ĐÃ là giao của (bot × người × ngữ cảnh)
        boolean allCollections,      // chỉ ADMIN
        String botSlug               // để tách cache và để log
) {
    /** PHẢI chứa tập collection, nếu không cache rò rỉ giữa các quyền. */
    public String cacheScopeKey() {
        if (allCollections) return "all";
        return botSlug + "#" + new TreeSet<>(readableCollections);
    }
}
```

`readableCollections` được tính **một lần** ở tầng vào (bot hoặc web), theo công thức
ở mục 4.4, rồi truyền xuống. `ChunkRepository.appendConditions` chỉ cần đổi
`c.category = ANY(?::text[])` thành `c.collection_id = ANY(?::bigint[])` — một điều kiện
duy nhất, giữ nguyên toàn bộ logic over-fetch cho pgvector.

---

## 3. Đăng nhập Entra ID cho giao diện quản trị

### 3.1 App registration

Một app registration **SingleTenant** (chỉ tenant BSC → tự động chỉ tài khoản
`@bsc.com.vn` đăng nhập được):

| Mục | Giá trị |
|---|---|
| Loại | Web, SingleTenant |
| Redirect URI | `https://<host>/login/oauth2/code/entra` |
| Front-channel logout | `https://<host>/logout` |
| Credential | **Certificate** (khuyến nghị) hoặc client secret |
| Graph permission (application) | `User.Read.All`, `GroupMember.Read.All` — cần admin consent |
| App roles | `RagAdmin`, `RagEditor`, `RagUser` |

> Dùng **certificate** thay client secret. Client secret hết hạn là bot chết im lặng —
> đây là nguyên nhân sự cố phổ biến nhất của bot Teams. Nếu buộc dùng secret thì đặt
> lịch nhắc gia hạn.

### 3.2 Thay đổi trong `SecurityConfig`

Hiện tại chỉ có **một** filter chain, `STATELESS`, CSRF tắt. OIDC login cần session →
phải tách thành **hai chain** theo thứ tự:

```java
@Bean @Order(1)
SecurityFilterChain apiChain(HttpSecurity http) {
    // /api/**, /api/messages, /actuator/**  → STATELESS
    // giữ ApiKeyAuthFilter cho máy-gọi-máy
    // thêm oauth2ResourceServer() nếu muốn nhận JWT bearer
}

@Bean @Order(2)
SecurityFilterChain uiChain(HttpSecurity http) {
    // /, /admin.html, /login/**  → session + oauth2Login()
    // CSRF BẬT LẠI (CookieCsrfTokenRepository) vì đã có cookie phiên
    // /admin.html: permitAll -> hasAuthority("APPROLE_RagAdmin")
}
```

Hai điểm dễ sai:

- **CSRF phải bật lại ở chain UI.** Chain hiện tại tắt CSRF là hợp lý khi xác thực bằng
  header API key. Khi có cookie phiên, tắt CSRF là mở lỗ CSRF thật.
- Frontend `app.js` gọi API bằng `X-API-Key` — phải chuyển sang gọi cùng phiên
  (`credentials: 'same-origin'`) + gửi CSRF token.

Kiểm tra bổ sung (đai an toàn thứ hai, không thay cho SingleTenant):

```java
// tid phải đúng tenant BSC, và upn phải kết thúc @bsc.com.vn
if (!tenantId.equals(props.getEntra().getTenantId())) throw ...;
if (!upn.toLowerCase().endsWith("@bsc.com.vn")) throw ...;
```

### 3.3 Lấy nhóm Entra: app role vs group claim vs Graph

| Cách | Ưu | Nhược |
|---|---|---|
| **App role** (`roles` claim) | Gọn, gán role cho nhóm Entra, không cần Graph | Chỉ được vài role thô; không biết người dùng thuộc phòng nào |
| **Group claim** (`groups`) | Có nhóm ngay trong token | Quá 200 nhóm → Entra thay bằng `_claim_names`/`_claim_sources`, buộc phải gọi Graph; và bot Teams **không có** token người dùng nên vẫn phải gọi Graph |
| **Graph app-only** | Nguồn duy nhất dùng được cho **cả** web và bot | Cần admin consent + cache |

**Khuyến nghị: App role cho role thô (`RagAdmin`/`RagEditor`/`RagUser`) + Graph app-only
cho membership nhóm.** Lý do quyết định: bot Teams chỉ có `aadObjectId`, không có token của
người dùng, nên **bắt buộc** phải có đường Graph app-only. Đã phải có nó thì dùng chung
cho cả web, để web và bot phân quyền bằng đúng một logic — hai đường phân quyền khác nhau
là công thức chắc chắn dẫn đến rò rỉ.

```java
/** Nhóm transitive của người dùng, cache 15 phút trong rag_users + Caffeine. */
public Set<UUID> groupsOf(UUID entraObjectId) { ... }
// GET /v1.0/users/{id}/transitiveMemberOf/microsoft.graph.group?$select=id,displayName
// hoặc POST /v1.0/users/{id}/getMemberGroups  (gọn hơn, chỉ trả id)
```

Cache là bắt buộc, không phải tối ưu: nếu không, mỗi tin nhắn Teams tốn thêm một round-trip
Graph, và Graph có throttling.

---

## 4. Phân quyền — tư vấn (yêu cầu 3)

### 4.1 Nguyên tắc phân chia: Entra giữ *danh tính*, app giữ *chính sách*

Đây là quyết định thiết kế quan trọng nhất của phần phân quyền:

| Thuộc về Entra ID | Thuộc về DB của app |
|---|---|
| Ai là ai, ai thuộc phòng nào (nhóm phòng ban, DL) | Bot nào đọc collection nào |
| Role thô: `RagAdmin` / `RagEditor` / `RagUser` | Ai là chủ bot nào (`rag_grants`) |
| Ai được **dùng** bot nào (`rag_bot_audience` trỏ tới nhóm Entra) | Bot nào phục vụ Team nào |
| Ai được **đọc** collection nào (`rag_collection_acl` trỏ tới nhóm Entra) | Persona, model, tham số |

Lý do: dữ liệu "ai thuộc phòng Kế toán" **đã tồn tại và đã được HR/IT duy trì** trong Entra.
Nhân bản nó vào app là tự nhận nợ đồng bộ — người chuyển phòng sẽ giữ quyền cũ.
Ngược lại, phần mịn ("anh B là chủ bot Pháp chế") mà đòi tạo nhóm Entra mỗi lần thì
sẽ chết ở khâu quy trình: mỗi bot mới phải mở ticket cho IT.

Cụ thể hoá:

```
rag_collection_acl.entra_group_id  → "Phòng Nhân sự" (nhóm Entra, IT quản lý)
rag_bot_audience.principal_id      → "Phòng Nhân sự" hoặc "Toàn thể CBNV"
rag_grants                          → "anh B là OWNER của bot #7"  (app quản lý)
```

### 4.2 Bốn lớp kiểm soát (defense in depth)

| Lớp | Chặn được gì | Bắt buộc? |
|---|---|---|
| 1. Teams app permission policy | Ai **thấy/cài** được app | Chỉ khả dụng ở Cách 2; coarse |
| 2. Kiểm tra audience trong bot | Người ngoài audience nhắn tin cho bot | **Bắt buộc** |
| 3. ACL collection ở tầng SQL | Bot bị cài sai chỗ vẫn không lộ tài liệu | **Bắt buộc** |
| 4. Giới hạn theo ngữ cảnh channel | Rò rỉ sang người khác trong channel | **Bắt buộc** (mục 4.4) |

Lớp 1 là bonus. **Lớp 3 mới là thứ thật sự bảo vệ dữ liệu** — vì nó nằm trong `WHERE` của
câu SQL, không phụ thuộc vào việc bot được cài đúng chỗ hay không.

### 4.3 Bốn role, đủ và không thừa

| Role | Ở đâu | Làm được gì |
|---|---|---|
| `RagAdmin` | App role Entra | Tạo/xoá bot, cấu hình hệ thống, eval, xem mọi hội thoại |
| `BOT OWNER` | `rag_grants` | Quản lý 1 bot: persona, model, gắn collection, xem hội thoại của bot đó |
| `COLLECTION EDITOR` | `rag_grants` | Upload/xoá/sửa tài liệu trong 1 collection |
| `RagUser` | App role Entra | Dùng bot (mặc định cho toàn thể CBNV) |

Kịch bản yêu cầu 3 ("một vài cán bộ vào upload tài liệu và tạo bot") ánh xạ thành:
gán `RagEditor` cho một nhóm Entra nhỏ (ví dụ `SG-RAG-Editors`), rồi Admin cấp
`rag_grants(OWNER, bot)` / `(EDITOR, collection)` cho từng người trong giao diện quản trị.
Cán bộ đó vào `/admin.html`, chỉ thấy bot và collection mình được cấp.

### 4.4 Bẫy quan trọng: channel không phải chat riêng

Đây là chỗ hầu hết hệ thống nội bộ làm sai.

Trong chat riêng, người hỏi là người duy nhất đọc câu trả lời → dùng quyền của người đó là đúng.
Trong **channel**, câu trả lời hiện ra cho **mọi thành viên channel**. Nếu bot dùng quyền của
người hỏi, một cán bộ Nhân sự @mention bot trong channel công khai sẽ **phát tán tài liệu
Nhân sự cho cả channel** — mà bot làm hoàn toàn đúng theo ACL của người hỏi.

Công thức phải là:

```java
Set<Long> readable(Bot bot, User asker, Context ctx) {
    Set<Long> base = collectionsOf(bot);                     // bot được gắn
    switch (ctx.scope()) {
        case PERSONAL -> base.retainAll(readableBy(asker.groups()));
        case CHANNEL  -> {
            // giao với quyền của TEAM, không phải của người hỏi
            base.retainAll(readableBy(Set.of(ctx.teamAadGroupId())));
            base.retainAll(readableBy(asker.groups()));       // người hỏi cũng phải có quyền
            base.removeIf(id -> !collection(id).channelAllowed());  // collection mật: chặn
        }
        case GROUP_CHAT -> base.retainAll(publicCollections()); // không suy được thành viên
    }
    return base;
}
```

Ba điểm:

- Channel dùng **giao của quyền team và quyền người hỏi** — chặt hơn cả hai.
- `rag_collections.channel_allowed = false` cho tài liệu mật (lương, nhân sự, hợp đồng):
  chỉ trả lời trong chat riêng, bất kể ai hỏi.
- Group chat ad-hoc không suy ra được thành viên một cách đáng tin → chỉ collection công khai.

Khi bot từ chối vì lý do quyền, **nói rõ là do quyền**, đừng nói "không tìm thấy tài liệu" —
người dùng sẽ hỏi lại mãi. Nhưng cũng đừng tiết lộ tên tài liệu.

### 4.5 Audit

Công ty chứng khoán sẽ bị hỏi "ai đã đọc gì". `rag_conversations` + `rag_messages` +
`rag_message_citations` đã lưu gần đủ; chỉ cần thêm `entra_object_id` và `bot_id`
(đã có trong V2) là truy vết được: người X, lúc Y, hỏi gì, bot nào trả lời, dựa trên
tài liệu nào. Nên giữ tối thiểu 1 năm và tách quyền xem log.

Lưu ý `rag.observability.log-questions=false` hiện tại là mặc định đúng — câu hỏi nằm
trong DB (có ACL), không nằm trong file log (thường ai cũng đọc được).

---

## 5. Nâng cấp chất lượng trả lời (yêu cầu 4)

Xếp theo **tác động / công sức**. Chỉ đổi một thứ mỗi lần và đo bằng bộ eval — quy trình
đã có trong [CLAUDE.md](../CLAUDE.md#khi-thay-đổi-hành-vi-trả-lời).

### 5.1 Đo trước đã (làm trước tất cả)

Không có baseline thì mọi thay đổi bên dưới chỉ là cảm giác.

- Xây bộ chuẩn **100–150 câu hỏi thật** (thu từ câu hỏi cán bộ hay hỏi, không phải câu tự nghĩ),
  gắn nhãn tài liệu/điều khoản đúng.
- Thêm **metric retrieval-only**: `recall@k`, `MRR`, `nDCG`. Rẻ, không cần LLM judge, chạy được
  mỗi lần đổi tham số. Hiện `EvalService` chỉ có faithfulness / answer-relevance / context-recall
  (đều cần LLM) → chạy chậm và tốn tiền nên sẽ không ai chạy thường xuyên.
- Nút 👎 trong UI → tự đẩy câu đó vào `rag_eval_cases` để review. Bộ chuẩn tự lớn lên.

### 5.2 Embedding tiếng Việt — đòn lớn nhất

Đang dùng `text-embedding-3-small` (1536). Với tiếng Việt, đây là điểm yếu nhất của pipeline.

Ứng viên: **`bge-m3`** (1024 chiều, multilingual, self-host qua Ollama hoặc TEI — repo đã hỗ trợ
`RAG_EMBEDDING_PROVIDER=OLLAMA`), `text-embedding-3-large` (3072), `voyage-multilingual-2`.

**Đã dựng sẵn đường đo** (`rag.embedding.trial.*`): nhúng lại chính các chunk đang có bằng
model ứng viên vào một bảng phụ, rồi so recall@k/MRR trên cùng bộ câu hỏi — index đang chạy
không bị đụng đến. Quy trình đầy đủ: [EMBEDDING-UPGRADE.md](EMBEDDING-UPGRADE.md).
Đổi thật chỉ khi hơn rõ ràng trên bộ ≥ 50 câu.

> Đổi model/số chiều ⇒ **nạp lại toàn bộ**, `SchemaValidator` sẽ chặn lúc khởi động nếu
> config lệch DB. Đây là bất biến, đừng lách.

Bonus của self-host `bge-m3`: dữ liệu tài liệu nội bộ không rời hạ tầng — thường là điều
kiện bắt buộc với công ty chứng khoán.

### 5.3 Rerank — **giữ LLM reranker** (quyết định 08/2026)

Chi phí Cohere chưa được duyệt, nên giữ `rag.rerank.provider=LLM`. Ghi lại để cân nhắc
sau, và những gì làm được ngay mà không tốn thêm:

- LLM rerank tốn **một lần gọi LLM mỗi câu hỏi**, đọc tới `retrieval.candidates` (36) ứng
  viên — thường là khoản tốn thứ hai sau bước sinh câu trả lời.
- Giảm chi phí mà không đổi provider: hạ `retrieval.candidates` (36 → 24) và dùng model rẻ
  cho `rag.internal.model`. Đo lại bằng `/eval/retrieval?includeRerank=true` để biết đã
  đánh đổi bao nhiêu — đây chính là lý do metric đó tồn tại.
- Khi có ngân sách: **Cohere `rerank-multilingual-v3.0`** là một dòng cấu hình
  (`rag.rerank.provider=COHERE` + `COHERE_API_KEY`), `CohereReranker` đã sẵn.
- Phương án miễn phí nhưng tốn hạ tầng: **`bge-reranker-v2-m3`** self-host qua TEI —
  ~50–100 ms, tiếng Việt tốt, dữ liệu không ra ngoài; cần GPU và phải viết thêm một
  `Reranker`.

> Giữ nguyên phân biệt `reliable` vs `degraded` của `RerankResult` — CLAUDE.md gọi đây là lỗi
> nghiêm trọng nhất của bản cũ. Reranker mới cũng phải báo `degraded` khi service chết,
> để `RelevanceGate` chuyển sang đánh giá bằng cosine chứ không im lặng từ chối.

### 5.4 Chunking cho văn bản pháp quy Việt Nam

`MarkdownChunker` bám heading Markdown. Văn bản nội bộ (quy chế, quy trình, quyết định)
có cấu trúc riêng mà heading Markdown không bắt được:

- Nhận diện `Chương`, `Mục`, `Điều \d+`, `Khoản`, `Điểm`, `Phụ lục` làm ranh giới chunk.
  **Không bao giờ cắt giữa một `Điều`** — trả lời "theo Điều 12" mà chỉ có nửa Điều 12
  là sai nghiêm trọng hơn không trả lời.
- ~~**Bảng**: giữ nguyên khối bảng, lặp lại dòng header khi buộc phải cắt.~~
  **Đã có sẵn** trong `MarkdownChunker.splitTable` — tôi nêu sai ở bản đầu.
- **Prefix giàu hơn khi embed.** Hiện chỉ prefix `heading_path`
  (`rag.chunking.prefix-heading-path=true`). Thêm **tên tài liệu + số văn bản + ngày hiệu lực**:

  ```
  [Quy chế nghỉ phép — QĐ-123/2026/QĐ-BSC — hiệu lực 01/01/2026]
  Chương II > Điều 7. Nghỉ phép hằng năm
  <nội dung chunk>
  ```

  Cải thiện rõ với các câu hỏi dạng "quy định số bao nhiêu", "văn bản nào quy định" —
  vì hiện tại thông tin đó **không có trong vector** của chunk.

### 5.5 Nhánh full-text — ba cải tiến nhỏ, rẻ

Trigger `rag_chunks_tsv_sync` hiện nối phẳng `heading_path + context + content`, mọi từ
cùng trọng số.

**a) Trọng số theo trường** — heading quan trọng hơn thân bài:

```sql
NEW.tsv := setweight(to_tsvector('vi', coalesce(NEW.heading_path,'')), 'A')
        || setweight(to_tsvector('vi', coalesce(NEW.context,'')),      'B')
        || setweight(to_tsvector('vi', coalesce(NEW.content,'')),      'C');
```

Cần `REINDEX` + cập nhật lại `tsv` (không cần nạp lại tài liệu, `markdown` đã lưu sẵn).

**b) Cụm từ.** `TsQueryBuilder.orQuery` ghép mọi từ bằng OR (đúng, và đừng quay lại
`plainto_tsquery`). Nhưng tiếng Việt là ngôn ngữ đa âm tiết: "nghỉ phép", "phụ cấp",
"ký quỹ" — OR từng âm tiết làm loãng kết quả. Thêm nhánh `phraseto_tsquery` cho các cụm
2–3 âm tiết cạnh nhau và cộng điểm khi khớp cụm.

**c) Nhánh thứ ba chống gõ sai.** `pg_trgm` đã bật. Thêm nhánh `similarity()` cho trường hợp
gõ sai chính tả / sai tên file, gộp vào RRF như nhánh thứ ba. RRF không cần chuẩn hoá thang
điểm nên thêm nhánh gần như miễn phí về mặt kiến trúc.

### 5.6 Từ điển thuật ngữ / viết tắt — bắt buộc với ngành chứng khoán

Cán bộ gõ "UBCK", "VSD", "T+2", "CTCK", "margin", "room ngoại"; tài liệu viết đầy đủ.
Vector search không tự nối được các cặp này một cách đáng tin.

```sql
CREATE TABLE rag_synonyms (
    id BIGSERIAL PRIMARY KEY,
    term TEXT NOT NULL,              -- 'UBCKNN'
    expansions TEXT[] NOT NULL,      -- {'Ủy ban Chứng khoán Nhà nước'}
    collection_id BIGINT,            -- NULL = toàn hệ thống
    UNIQUE (term, collection_id)
);
```

Dùng ở hai chỗ: (1) chèn các cặp liên quan vào prompt của `QueryPlanner` để câu viết lại
dùng đúng thuật ngữ tài liệu; (2) mở rộng `tsquery` ở nhánh full-text. Đây là loại cải tiến
"nhỏ mà thấy ngay", và giao diện quản trị nên cho cán bộ tự thêm.

### 5.7 Chất lượng câu trả lời

- **Kiểm tra trích dẫn sau khi sinh.** LLM đôi khi ghi `[3]` khi chỉ có 2 nguồn. Rẻ nhất:
  validate marker, bỏ marker không tồn tại. Đầy đủ hơn: kiểm tra từng câu có nguồn đỡ,
  câu không có nguồn thì gắn nhãn hoặc cắt.
- **Hợp đồng định dạng câu trả lời.** Với tài liệu nội quy, cán bộ cần *căn cứ*, không chỉ
  tên file. Bắt buộc cấu trúc: câu trả lời trực tiếp → chi tiết →
  `Căn cứ: Điều 7, QĐ-123/2026/QĐ-BSC (hiệu lực 01/01/2026)`.
- ~~**Xử lý xung đột phiên bản.**~~ **Đã có sẵn, tôi nêu sai ở bản đầu.** `PromptBuilder`
  đã đưa `hieu_luc` vào thẻ `<tai_lieu>` và system prompt đã yêu cầu "ưu tiên tài liệu có
  ngày hiệu lực mới hơn". Không cần làm gì thêm.
- **Prompt caching (Anthropic).** System prompt + persona + hướng dẫn định dạng là phần
  cố định và dài → cache được, giảm chi phí và độ trễ rõ rệt.
- **Trả lời "không biết" cho đúng.** `RelevanceGate` đã có. Với bot mới, thêm gợi ý hành động:
  "chưa có tài liệu về việc này — bạn có thể hỏi <chủ bot> hoặc gửi yêu cầu bổ sung tài liệu".

### 5.8 Vượt khỏi tra cứu — "trợ giúp cán bộ" (yêu cầu 1, nửa sau)

Sau khi phần tra cứu đã ổn, thêm tầng **tool calling** để bot làm được việc chứ không chỉ đọc:
tra danh bạ nội bộ, tính ngày phép còn lại, tra trạng thái đề nghị/ticket, tạo yêu cầu,
tóm tắt tài liệu người dùng đính kèm ngay trong Teams.

Cần: `LlmClient` bổ sung tool-use (Anthropic SDK hỗ trợ sẵn), và một `ToolRegistry` khai báo
tool theo bot (bot Nhân sự có tool phép, bot IT có tool ticket). **Xếp sau cùng** — tool calling
trên nền RAG kém chỉ làm cái sai lan rộng hơn.

### 5.9 Nguồn tài liệu: đồng bộ SharePoint thay vì upload tay

Nguyên nhân số một khiến chatbot nội bộ chết dần: **tài liệu cũ**. Upload tay thì sau 3 tháng
không ai upload nữa và bot trả lời theo quy định đã bị thay.

Công ty đã dùng M365 → tài liệu thật nằm ở SharePoint/Teams Files. Nên coi SharePoint là
nguồn sự thật và đồng bộ theo `delta`:

```sql
CREATE TABLE rag_sources (
    id BIGSERIAL PRIMARY KEY,
    kind TEXT NOT NULL,               -- SHAREPOINT | LOCAL_FOLDER | MANUAL
    collection_id BIGINT NOT NULL REFERENCES rag_collections(id),
    drive_id TEXT, folder_path TEXT,
    delta_token TEXT,                 -- Graph delta: chỉ lấy file đã đổi
    sync_cron TEXT, last_sync_at TIMESTAMPTZ, last_error TEXT
);
```

`@Scheduled` (đã có `@EnableScheduling` trong `AppConfig`) + Graph delta query → chỉ nạp lại
file đã đổi. `rag.ingestion.skip-unchanged=true` và `content_sha256` sẵn có làm phần chống nạp lại.

Giữ upload tay cho tài liệu không nằm trên SharePoint.

---

## 6. Triển khai & vận hành

**Endpoint công khai.** Azure Bot Service phải gọi được `POST /api/messages` từ Internet.
Hai lựa chọn:

1. **RAG on-prem, publish endpoint qua reverse proxy/WAF ở DMZ.** Dữ liệu không rời hạ tầng.
   Khuyến nghị cho công ty chứng khoán.
2. **Bot façade trên Azure (App Service/Container Apps), gọi RAG on-prem qua VPN/Private Link.**
   Phức tạp hơn nhưng cách ly tốt hơn.

Không thể allowlist IP của Bot Service một cách đáng tin → **bảo mật dựa vào kiểm tra JWT**
(mục 1). Đó là biện pháp chính, và nó đủ mạnh — miễn là kiểm tra đủ 5 điều kiện trong bảng.

**Bí mật.** Hiện tất cả qua biến môi trường. Với bot thì thêm `MicrosoftAppId`,
`MicrosoftAppPassword`/cert, `GRAPH_*`. Nên chuyển sang Azure Key Vault hoặc ít nhất một
secrets file ngoài repo, có phân quyền hệ điều hành.

**Giới hạn tần suất.** ✅ Ba tầng, mỗi tầng chặn một kiểu lạm dụng khác nhau:

| Tầng | Ở đâu | Đơn vị đếm |
|---|---|---|
| Người dùng web/API | `RateLimitFilter` | `clientId` — với đường Entra **chính là `entra_object_id`** |
| Người dùng Teams | `TeamsBotService.withinRateLimit` | `aadObjectId` (`rag.bot.per-user-per-minute`) |
| Cả một bot | `TeamsBotService.withinBotRateLimit` | slug bot (`rag.bot.per-bot-per-minute`) |

Tầng thứ ba là thứ mới: hạn mức theo người **không đủ**, vì một bot cài cho cả công ty
thì 200 người mỗi người hỏi một câu vẫn là 200 lượt/phút × 3–4 lời gọi LLM — đủ quay hết
hạn mức nhà cung cấp và làm chết cả những bot khác lẫn giao diện web. Tầng này nằm sau
hàng đợi, sau khi đã giải được bot, nhưng **trước** toàn bộ phần tốn tiền.

**Giám sát theo bot.** ✅ Hai nguồn, cố ý tách đôi vì phục vụ hai mục đích khác nhau:

- **Micrometer** (`/actuator/prometheus`) — mọi số đo của một lượt hỏi–đáp mang tag `bot`
  (`rag.questions`, `rag.questions.abstained`, `rag.abstain.reason`, `rag.cache.hits`,
  `rag.errors`, `rag.citations.invalid`, `rag.tokens`, `rag.cost.usd`, `rag.latency`).
  Số liệu nằm trong bộ nhớ tiến trình → dùng để **cảnh báo thời gian thực**.
  Đường không qua bot nào mang tag `bot="web"`, không bao giờ để tag rỗng.
- **DB** (`rag_messages.bot_slug`, migration V6) — `GET /api/v1/rag/admin/reports/bots`
  đọc trực tiếp từ lịch sử hội thoại nên **không mất khi restart** và có p95 thật
  (`percentile_cont`), 👍/👎 nối qua `rag_feedback`. Dùng để **giải trình**.

Đọc bảng báo cáo: **từ chối cao** = collection của bot đó thiếu tài liệu;
**👎 cao mà từ chối thấp** = bot trả lời tự tin nhưng sai, nguy hiểm hơn nhiều.
`GET /api/v1/rag/admin/reports/gaps` biến con số đó thành việc làm được: các câu hỏi bị
từ chối nhiều nhất, gom theo câu hỏi chuẩn hoá — chính là danh sách tài liệu cần nạp.

---

## 7. Lộ trình

| Giai đoạn | Nội dung | Ước lượng |
|---|---|---|
| **P0 — Bịt lỗ + đo baseline** | ~~Đưa role vào `cacheScopeKey`~~ ✅; ~~gate `/admin.html`~~ ✅; bỏ `allDepartments=true` ở webhook Teams (dời sang P2); bộ eval 100 câu + metric recall@k | 1 tuần |
| **P1 — Entra SSO** ✅ **đã làm** | App registration; tách 2 filter chain; `oauth2Login`; app role; Graph app-only + cache; CSRF; `app.js` dùng phiên. Hướng dẫn: [ENTRA-SETUP.md](ENTRA-SETUP.md) | xong |
| **P2 — Bot Teams thật** ✅ **đã làm** | `/api/messages` + validate JWT; client gửi activity; xử lý bất đồng bộ + typing; Adaptive Card có nguồn; `aadObjectId` → ACL; Teams app manifest. Hướng dẫn: [TEAMS-BOT-SETUP.md](TEAMS-BOT-SETUP.md) | xong |
| **P3 — Nền tảng nhiều bot** ✅ **đã làm** | Schema V3 (collections/bots/audience/channels/grants); định tuyến bot; ACL trong DB; giới hạn ngữ cảnh channel; persona theo bot; UI tạo/cấu hình bot | xong |
| **P4 — Chất lượng** 🟡 **gần xong** | ✅ metric recall@k/MRR; ✅ chunker pháp quy; ✅ trọng số `tsv`; ✅ từ điển thuật ngữ; ✅ kiểm tra trích dẫn; ✅ định danh tài liệu vào vector; ✅ đường so sánh embedding song song ([EMBEDDING-UPGRADE.md](EMBEDDING-UPGRADE.md)). ⏳ chạy so sánh trên kho thật rồi đổi (cần bộ chuẩn + cửa sổ nạp lại). Rerank giữ LLM theo quyết định chi phí | còn lại: 1 tuần + cửa sổ nạp lại |
| **P4b — Vận hành theo bot** ✅ **đã làm** | `bot_slug` trong `rag_messages` (V6); tag `bot` cho mọi metric; hạn mức theo bot; `GET /admin/reports/{bots,daily,gaps}`; tab "Báo cáo" | xong |
| **P5 — Mở rộng** | Đồng bộ SharePoint; tool calling; cảnh báo tự động theo ngưỡng báo cáo | sau |

Ranh giới quan trọng: **P0 và P1 không phụ thuộc Azure admin**, làm được ngay.
P2 cần IT tạo Azure Bot resource + app registration + approve Teams app → **mở ticket cho IT
ngay khi bắt đầu P1**, đây thường là đường tới hạn dài nhất.

---

## 8. Việc cần chốt với IT / bộ phận tuân thủ

Danh sách này nên gửi cho IT sớm — thời gian chờ ở đây thường dài hơn thời gian code:

1. Tạo **app registration SingleTenant** cho web admin (redirect URI, certificate),
   admin consent cho `User.Read.All` + `GroupMember.Read.All`.
2. Tạo **Azure Bot resource** + app registration cho bot, cấp quyền publish Teams app
   vào app catalog của tổ chức.
3. Xác nhận **danh sách nhóm Entra** dùng làm ACL (nhóm phòng ban đã có chưa? tên là gì?
   ai duy trì? người chuyển phòng có được cập nhật nhóm không?).
4. Nhóm Entra cho **người quản trị hệ thống RAG** (`SG-RAG-Admins`) và người được upload
   (`SG-RAG-Editors`).
5. Quyết định **hạ tầng**: publish endpoint on-prem qua DMZ, hay façade trên Azure.
6. Tuân thủ: câu hỏi/câu trả lời được lưu bao lâu, ai xem được log, dữ liệu tài liệu có được
   phép gửi ra API bên ngoài (OpenAI/Anthropic/Cohere) hay bắt buộc self-host.

Câu số 6 có thể đảo ngược vài lựa chọn ở mục 5 — nên hỏi trước khi bắt đầu P4.
Nếu bắt buộc self-host thì đường đi là: `bge-m3` (embedding) + `bge-reranker-v2-m3` (rerank)
+ một LLM chạy local cho phần sinh câu trả lời, tất cả qua Ollama/TEI mà repo đã hỗ trợ.
