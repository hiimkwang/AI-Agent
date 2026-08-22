# Cài đặt Azure / Entra cho AI-Agent — máy chủ UAT

Tài liệu thao tác, viết cho người ngồi trước Azure Portal. Áp dụng cho tenant
**BIDV Securities JSC** đang ở gói **Microsoft Entra ID Free**.

Thiết kế và lý do từng lựa chọn: [BOT-PLATFORM.md](BOT-PLATFORM.md).
Tài liệu chung không gắn với máy chủ cụ thể: [ENTRA-SETUP.md](ENTRA-SETUP.md),
[TEAMS-BOT-SETUP.md](TEAMS-BOT-SETUP.md).

---

## 0. Trước khi bắt đầu

### 0.1 Gói Free ảnh hưởng gì

Gán **app role cho nhóm** cần Entra ID **P1/P2**. Portal báo:

> *Groups are not available for assignment due to your Active Directory plan level.*

**Không cần mua P1.** Ứng dụng có sẵn đường khác: suy vai trò từ **thành viên nhóm đọc
qua Microsoft Graph** (`rag.entra.admin-groups`), mà đọc nhóm qua Graph **không bị giới
hạn theo gói**. Xem [EntraScopeService.java:100](../src/main/java/com/ai/aiagent/security/EntraScopeService.java#L100)
và test `EntraScopeServiceTest` — *"Thanh vien admin-groups => ADMIN, khong can app role"*.

Hệ quả: **bỏ toàn bộ phần app role**. Không mất chức năng gì, vì ứng dụng chỉ có hai vai
trò thật là `ADMIN` và `USER`, mà `RagEditor`/`RagUser` vốn đều chỉ ánh xạ ra `USER`.

### 0.2 Sẽ tạo HAI app registration khác nhau

| | Dùng để | Biến môi trường |
|---|---|---|
| **App 1** — `BSC RAG Assistant` | Đăng nhập + tra nhóm qua Graph để phân quyền tài liệu | `ENTRA_CLIENT_ID` / `ENTRA_CLIENT_SECRET` |
| **App 2** — Azure Bot tự sinh | Danh tính của bot khi nói chuyện với Bot Framework | `BOT_APP_ID` / `BOT_APP_PASSWORD` |

Tách riêng là có chủ đích: App 1 nắm quyền đọc toàn bộ danh bạ và nhóm của công ty. Gộp
vào bot nghĩa là lộ secret của bot thì mất luôn quyền đó.

### 0.3 Quyền của người thao tác

| Việc | Vai trò tối thiểu |
|---|---|
| Tạo app registration | **Application Developer** |
| Bấm **Grant admin consent** | **Global Administrator** hoặc **Privileged Role Administrator** |
| Tạo Azure Bot resource | **Contributor** trên subscription |
| Upload Teams app | **Teams Administrator** |

Bốn vai trò này có thể là bốn người. Xác định trước ai làm bước nào.

### 0.4 Một hostname duy nhất, dưới tiền tố `/rag/`

Kiểm chứng trên máy chủ ngày 19/08/2026:

| | Địa chỉ | Ai vào được |
|---|---|---|
| **Giao diện web** | `https://chatbot-uat.bsc.com.vn/rag/` | Internet, HTTPS, cert hợp lệ |
| **Endpoint bot** | `https://chatbot-uat.bsc.com.vn/rag/api/messages` | Azure Bot Service |
| Đường nội bộ dự phòng | `http://chatbot-uat2.bsc.com.vn/rag/` | Chỉ trong mạng BSC |

Đường đi:

```
Internet → 103.219.180.171 (IP của BSC) → 10.21.170.54 (kết thúc TLS)
         → 10.21.170.55:80 (Apache) → 127.0.0.1:8080/rag/ (ứng dụng)
```

Vì dùng lại `chatbot-uat.bsc.com.vn` — hostname đã có DNS công cộng và đã nằm trong SAN
của cert — **không phải xin bản ghi DNS mới, không phải xin cert mới**. Drupal vẫn giữ
`/`, ứng dụng RAG nhận `/rag/`.

`chatbot-uat2.bsc.com.vn` chỉ có bản ghi trên DNS nội bộ và **không** nằm trong SAN của
cert, nên không dùng được từ Internet và không dùng được với HTTPS. Giữ lại làm đường vào
nội bộ khi cần chẩn đoán mà không đi qua vhost của Drupal.

### 0.5 Tiền tố `/rag` phải khớp ở ba nơi

Lệch một chỗ là hỏng, và triệu chứng khác nhau ở từng chỗ:

| Nơi | Giá trị | Lệch thì bị gì |
|---|---|---|
| `/app/aiagent/config/aiagent.env` | `SERVER_CONTEXT_PATH=/rag` | Apache proxy tới đường không tồn tại → 404 toàn bộ |
| `/etc/httpd/conf.d/chatbot.conf` | `ProxyPass /rag/ → :8080/rag/` | Như trên |
| Entra Redirect URI | `…/rag/login/oauth2/code/entra` | `AADSTS50011` sau khi chọn tài khoản |

> **Bẫy khi sửa frontend về sau:** giao diện dựng đường dẫn qua hàm `url()` trong
> [app.js](../src/main/resources/static/app.js), lấy tiền tố từ chính `src` của `app.js`.
> Thêm một `fetch('/api/...')` tuyệt đối mà **không** bọc `url()` thì chạy đúng ở gốc và
> **404 dưới tiền tố** — lỗi chỉ hiện trên máy chủ, không hiện khi chạy local.

---

## 1. App 1 — Entra app registration

### 1.1 Tạo đăng ký

`portal.azure.com` → **Microsoft Entra ID** → **App registrations** → **+ New registration**

| Trường | Điền |
|---|---|
| Name | `BSC RAG Assistant` |
| Supported account types | **Accounts in this organizational directory only (Single tenant)** |
| Redirect URI | Platform **Web** → `https://chatbot-uat.bsc.com.vn/rag/login/oauth2/code/entra` |

→ **Register**

Sau khi Register, vào **Authentication** → **Add URI**, thêm URI thứ hai:

```
http://localhost:8080/rag/login/oauth2/code/entra
```

> **Vì sao hai cái:** cái đầu là đường dùng thật. Cái `localhost` là **đường vào dự phòng
> qua SSH tunnel** (mục 4.4) — cần đến khi Apache hoặc vhost của Drupal có sự cố. Azure
> chỉ chấp nhận `http://` cho `localhost`; mọi host khác bắt buộc `https://`, nên không
> thể khai `http://10.21.170.55:8080`.
>
> **Cả hai URI đều có `/rag`.** Tiền tố do `SERVER_CONTEXT_PATH` sinh ra ở tầng ứng dụng,
> không phải do Apache thêm vào — nên nó có mặt kể cả khi SSH tunnel nối thẳng vào cổng
> 8080, bỏ qua Apache.
>
> Sai một ký tự là `AADSTS50011`. Chú ý: không có `/` ở cuối, và là `/login/oauth2/code/entra`
> chứ không phải `/oauth2/authorization/entra` (cái sau là đường *bắt đầu* đăng nhập).

### 1.2 Ghi lại hai mã định danh

Trang **Overview** hiện ra ngay sau khi Register:

| Nhãn trên Portal | Biến |
|---|---|
| **Directory (tenant) ID** | `ENTRA_TENANT_ID` |
| **Application (client) ID** | `ENTRA_CLIENT_ID` |

### 1.3 Tạo credential

**Certificates & secrets**

**Nên dùng — Certificate:** tab *Certificates* → **Upload certificate** (`.cer`/`.pem`,
phần công khai).

**Nhanh hơn — Client secret:** tab *Client secrets* → **+ New client secret**
→ Description `rag-graph` → Expires **chọn hạn dài nhất được phép** → **Add**

> ⚠️ **Copy cột `Value` ngay lập tức.** Rời trang là không xem lại được. Cột **`Secret ID`
> không phải secret** — nhầm hai cột này là lỗi phổ biến nhất ở bước này.

> ⚠️ **Ghi hạn secret vào lịch ngay hôm nay.** Secret hết hạn làm hệ thống chết *im lặng*:
> người dùng vẫn đăng nhập bình thường nhưng đọc được **0 tài liệu**, vì Graph lỗi thì hệ
> thống **đóng quyền lại** chứ không mở. Đây là sự cố phổ biến nhất của tích hợp Entra.

→ `ENTRA_CLIENT_SECRET`

### 1.4 Quyền Microsoft Graph — bước quan trọng nhất

**API permissions** → **+ Add a permission** → **Microsoft Graph**

Màn hình tiếp theo hỏi loại quyền:

> ### 🔴 Chọn **Application permissions**, KHÔNG chọn *Delegated permissions*
>
> Ứng dụng gọi Graph bằng danh tính của chính nó (client credentials), không thay mặt
> người dùng đang đăng nhập. Chọn nhầm *Delegated* thì mọi lời gọi Graph trả rỗng —
> người dùng đăng nhập được nhưng **không đọc được tài liệu nào và không có thông báo
> lỗi nào trên màn hình**.

Tích hai quyền:

| Quyền | Để làm gì |
|---|---|
| `User.Read.All` | Đọc hồ sơ (tên, phòng ban) để hiển thị và ghi nhật ký kiểm toán |
| `GroupMember.Read.All` | Đọc nhóm của người dùng — **toàn bộ phân quyền dựa trên cái này** |

→ **Add permissions**

Sau đó bấm **✓ Grant admin consent for BIDV Securities JSC** → **Yes**

**Nghiệm thu bước này:** cột *Status* của **cả hai** dòng phải hiện
**✅ Granted for BIDV Securities JSC** màu xanh. Còn dấu ⚠️ vàng là chưa xong.

> Vì đã bỏ app role, **đây là điểm chết duy nhất của toàn bộ phân quyền** — cả vai trò
> ADMIN lẫn quyền đọc tài liệu đều đi qua Graph. Sai bước này thì không ai là admin và
> không ai đọc được gì.

### 1.5 Tắt "Assignment required"

Đây là **menu khác** — không phải App registrations:

**Microsoft Entra ID** → **Enterprise applications** → `BSC RAG Assistant` → **Properties**

| Trường | Đặt |
|---|---|
| Enabled for users to sign-in? | **Yes** |
| **Assignment required?** | **No** |
| Visible to users? | **No** |

→ **Save**

> ⚠️ **Bắt buộc.** Để **Yes** mà gói Free không gán được nhóm thì Azure **chặn đăng nhập**
> của mọi người chưa được gán tay — đúng cái vòng luẩn quẩn đã gặp.

**"No" không có nghĩa là mở toang.** Còn ba lớp chặn, và lớp quan trọng nhất ở tầng dữ liệu:

| Lớp | Cơ chế | Chặn ai |
|---|---|---|
| 1 | App registration **Single tenant** | Tài khoản ngoài BIDV Securities |
| 2 | `rag.entra.allowed-email-domains=bsc.com.vn` | Tài khoản khách (guest) trong tenant |
| 3 | **Không thuộc nhóm được cấp ⇒ đọc được 0 tài liệu** | Người trong công ty chưa được phân quyền |

Lớp 3 mới là thứ thật sự bảo vệ tài liệu. Người chưa được cấp nhóm đăng nhập vào chỉ
thấy màn hình chat rỗng — hỏi gì cũng "không tìm thấy tài liệu".

### 1.6 Chuẩn bị các nhóm

**Microsoft Entra ID** → **Groups**

**a) Nhóm quản trị RAG** — nếu chưa có thì tạo mới:
**+ New group** → Group type **Security** → Group name `RAG - Quản trị` → thêm 2–3 người
→ **Create**. Mở lại nhóm, copy **Object ID**.

**b) Nhóm phòng ban** — mở từng nhóm sẵn có, copy **Object ID**.

Lập bảng bàn giao:

```
[Quản trị]  RAG - Quản trị     00000000-1111-2222-3333-444444444444
[Phòng ban] Nhân sự            8f4e1c2a-...
[Phòng ban] Kế toán            9a5b2d3e-...
[Phòng ban] Pháp chế           ...
```

> Object ID (GUID), **không phải** tên nhóm và **không phải** địa chỉ email của nhóm.

---

## 2. App 2 — Azure Bot resource

### 2.1 Tạo resource

**Create a resource** → tìm **Azure Bot** → **Create**

| Trường | Điền |
|---|---|
| Bot handle | `bsc-rag-assistant` — **phải duy nhất toàn cầu**, trùng thì Azure báo lỗi |
| Subscription / Resource group | Theo chuẩn BSC |
| Pricing tier | **F0 (Free)** — đủ cho Teams |
| Type of App | **Multi Tenant** |
| Creation type | **Create new Microsoft App ID** |

→ **Review + create** → **Create**

> Multi Tenant là mặc định và đơn giản nhất. Nếu chính sách bắt buộc Single Tenant, báo
> lại — cần thêm `BOT_APP_TYPE=SINGLE_TENANT` và `BOT_TENANT_ID`, và đây là chỗ hay sai
> nhất (bot multi-tenant xin token ở tenant **ảo** `botframework.com`, không phải tenant
> công ty; nhầm là 401 khó hiểu).

### 2.2 Messaging endpoint

Vào resource → **Settings** → **Configuration** → **Messaging endpoint**:

```
https://chatbot-uat.bsc.com.vn/rag/api/messages
```

→ **Apply**

> `ProxyPass` trên Apache **đã dựng xong** (mục 4.1), nhưng endpoint chỉ sống khi
> `BOT_ENABLED=true`. Cứ điền trước — Azure không kiểm tra lúc lưu.

### 2.3 App ID và password

Vẫn ở trang **Configuration**:

- **Microsoft App ID** → `BOT_APP_ID`
- Bấm **Manage Password** (link cạnh App ID) → nhảy sang app registration **thứ hai** do
  Azure tự tạo → **Certificates & secrets** → **+ New client secret** → copy cột
  **Value** → `BOT_APP_PASSWORD`

> Lại nhắc: copy **`Value`**, không phải `Secret ID`. Và đây là **secret khác** với secret
> ở mục 1.3 — đừng dùng lẫn.

### 2.4 Bật kênh Teams

**Settings** → **Channels** → **Microsoft Teams** → chấp nhận điều khoản → **Apply**

Kênh phải hiện trạng thái **Running**.

---

## 3. Đóng gói và cài Teams app

### 3.1 Sửa manifest

Mở [teams-app/manifest.json](../teams-app/manifest.json), thay hai chỗ:

| Placeholder | Thay bằng |
|---|---|
| `{{BOT_APP_ID}}` (2 chỗ) | Microsoft App ID ở mục 2.3 |
| `{{APP_HOST}}` | `chatbot-uat.bsc.com.vn` — chỉ tên miền, **không** có `https://` |

### 3.2 Thêm icon

Đặt cạnh `manifest.json`:

- `color.png` — **192×192**, nền đầy màu
- `outline.png` — **32×32**, chỉ màu trắng trên nền trong suốt

### 3.3 Nén

```powershell
cd teams-app
Compress-Archive -Path manifest.json,color.png,outline.png `
                 -DestinationPath ..\bsc-rag-assistant.zip -Force
```

> ⚠️ Phải là **ba file phẳng**, không bọc thư mục. Nén cả thư mục là lỗi upload phổ biến
> nhất — Teams chỉ báo "manifest không hợp lệ" mà không nói lý do thật.

### 3.4 Upload

**Teams Admin Center** → **Teams apps** → **Manage apps** → **Upload new app** → chọn zip.

### 3.5 Ai được cài bot

**Teams apps** → **Permission policies** → tạo policy cho phép app này → gán cho nhóm
người dùng.

Đây là lớp kiểm soát "ai *thấy và cài* được bot". **Đừng dựa vào nó để bảo vệ dữ liệu** —
lớp thật sự bảo vệ là ACL tài liệu trong câu SQL, vì nó vẫn đúng kể cả khi bot bị cài
sai chỗ.

---

## 4. Việc phía máy chủ

Phần này người vận hành ứng dụng làm, sau khi nhận đủ thông tin bàn giao.

> **Thứ tự: nạp tài liệu và tạo collection TRƯỚC khi bật `ENTRA_ENABLED`.**
> Chưa có collection nào gắn `aclGroups` thì `PlatformService.hasNoAcl()` trả `true`,
> `EntraScopeService` quay sang bảng `rag.entra.group-departments` (rỗng), nên **mọi
> người không phải admin đọc được 0 tài liệu**. Triệu chứng giống hệt lỗi Graph — đăng
> nhập bình thường, hỏi gì cũng "không tìm thấy" — và sẽ đốt cả buổi để tìm nhầm chỗ.
> Bật Entra khi kho tài liệu còn rỗng thì chỉ nghiệm thu được tới bước 5.2.

### 4.1 Apache — ĐÃ XONG

Đã thêm vào `/etc/httpd/conf.d/chatbot.conf` (vhost của Drupal), bản gốc lưu ở
`chatbot.conf.bak-20260819`:

```apache
ProxyPreserveHost On
RedirectMatch ^/rag$ /rag/
<Location /rag/>
    RequestHeader setifempty X-Forwarded-Proto https
    ProxyPass        http://127.0.0.1:8080/rag/ flushpackets=on connectiontimeout=5 timeout=300
    ProxyPassReverse http://127.0.0.1:8080/rag/
</Location>
```

Ba chi tiết không đọc được từ code:

- **`<Location>` chứ không đặt ở mức vhost.** `RequestHeader` ở mức vhost sẽ gắn
  `X-Forwarded-Proto` cho cả request của Drupal, làm Drupal tự sinh URL `https://` —
  không liên quan gì đến ta mà lại đổi hành vi site đang chạy.
- **`timeout=300`.** Mặc định 60s cắt ngang câu trả lời của LLM.
- **`flushpackets=on`.** Không có nó thì endpoint trả lời theo dòng (SSE) bị đệm lại,
  người dùng chờ hết câu mới thấy chữ đầu tiên.

Vhost nội bộ `/etc/httpd/conf.d/rag-uat2.conf` cũng trỏ cùng tiền tố `/rag/`, và
chuyển `/` → `/rag/`.

> Tên file `rag-uat2.conf` sắp **sau** `chatbot.conf` là có chủ đích: Apache nạp
> `conf.d/*.conf` theo thứ tự chữ cái và vhost nạp đầu tiên thành **mặc định** cho cổng
> 80. Đổi thành tên sắp trước (ví dụ `aiagent.conf`) sẽ khiến mọi request không khớp
> hostname nào rơi vào ứng dụng RAG thay vì Drupal.

**Endpoint bot** dùng chung `<Location /rag/>` ở trên, không cần thêm gì. Nhưng bot đang
tắt thì `/rag/api/messages` trả **HTTP 500 kèm stack trace** thay vì 404 — chỉ bật
`BOT_ENABLED=true` khi đã có `BOT_APP_ID`, đừng để endpoint hở lâu ngoài Internet.

### 4.2 `aiagent.env`

Bỏ dấu `#` và điền vào `/app/aiagent/config/aiagent.env`:

```ini
ENTRA_ENABLED=true
ENTRA_TENANT_ID=<Directory (tenant) ID>
ENTRA_CLIENT_ID=<Application (client) ID>
ENTRA_CLIENT_SECRET=<Value ở mục 1.3>
ENTRA_ADMIN_GROUPS=<Object ID nhóm "RAG - Quản trị">
ENTRA_BOOTSTRAP_ADMINS=<email của bạn>

BOT_ENABLED=true
BOT_APP_ID=<Microsoft App ID>
BOT_APP_PASSWORD=<Value ở mục 2.3>
```

```bash
systemctl restart aiagent
```

### 4.3 Phòng ban → nhóm tài liệu

Không cấu hình trong file properties. Tạo collection kèm ACL nhóm qua REST:

```bash
curl -s -X POST http://127.0.0.1:8080/rag/api/v1/rag/admin/collections \
  -H "X-API-Key: $RAG_ADMIN_API_KEY" -H 'Content-Type: application/json' \
  --data-binary @collection.json
```

`collection.json` (UTF-8) — **tạo nhóm và cấp quyền là HAI lời gọi**, không gộp:

```json
{
  "slug": "nhan-su",
  "name": "Tài liệu Nhân sự",
  "description": "Quy chế, quy trình của Ban Nhân sự",
  "channelAllowed": false
}
```

Lời gọi trả về `id`. Cấp quyền đọc bằng lời gọi thứ hai:

```bash
curl -s -X PUT http://127.0.0.1:8080/rag/api/v1/rag/admin/collections/<id>/acl \
  -H "X-API-Key: $RAG_ADMIN_API_KEY" -H 'Content-Type: application/json' \
  -d '{"groupIds":["8f4e1c2a-1111-2222-3333-444444444444"],"groupNames":["Nhân sự"]}'
```

- `slug` **chính là** cột `category` của tài liệu đã nạp — phải khớp.
- `groupIds` là Object ID nhóm Entra ở mục 1.6; `groupNames` chỉ để người đọc nhật ký
  hiểu, không dùng để phân quyền.
- **Tạo nhóm mà quên gọi ACL ⇒ không ai đọc được** (ACL rỗng = đóng).
- `channelAllowed=false`: bot **từ chối** dùng nhóm tài liệu này trong channel Teams. Chỉ
  bật `true` cho tài liệu chấp nhận được việc cả channel cùng đọc.

### 4.4 Các đường vào giao diện

| Đường | Dùng cho | SSO |
|---|---|---|
| `https://chatbot-uat.bsc.com.vn/rag/` | Đường chính, dùng được từ Internet | ✅ |
| `http://chatbot-uat2.bsc.com.vn/rag/` | Nội bộ, khi cần tránh vhost Drupal | ❌ Microsoft từ chối redirect `http://` |
| SSH tunnel → `http://localhost:8080/rag/` | Dự phòng khi Apache có sự cố | ✅ (khớp URI localhost ở 1.1) |
| `http://10.21.170.55:8080/rag/` | Chỉ khi SSO còn tắt | ❌ `AADSTS50011` |

SSH tunnel:

```bash
ssh -L 8080:127.0.0.1:8080 root@10.21.170.55
# rồi mở trình duyệt: http://localhost:8080/rag/admin.html
```

Đường REST bằng API key **không bị ảnh hưởng bởi SSO** — hai cơ chế chạy song song, có
chủ đích, dùng được trong mọi trường hợp trên:

```bash
curl -H "X-API-Key: $RAG_ADMIN_API_KEY" http://127.0.0.1:8080/rag/api/v1/rag/admin/platform
```

## 5. Nghiệm thu

Chạy theo thứ tự. Sai bước nào dừng ở bước đó.

**1. Hệ thống đã nhận SSO**

```bash
curl -s https://chatbot-uat.bsc.com.vn/rag/api/v1/rag/me
# => {"ssoEnabled":true,"loginUrl":"/oauth2/authorization/entra","authenticated":false}
```

`loginUrl` trả về **không** có tiền tố `/rag` — đúng như vậy. Frontend tự ghép tiền tố
qua `url()`; máy chủ không biết mình đang được mount ở đâu.

**2. Graph chạy được** — đăng nhập qua tunnel rồi mở lại `/api/v1/rag/me`:

```json
{ "authenticated": true, "admin": true, "entraGroups": ["...", "..."], "departments": ["..."] }
```

- `entraGroups` **rỗng** ⇒ Graph chưa chạy: kiểm tra lại mục 1.4 (consent / nhầm
  Delegated) và secret.
- `admin: false` ⇒ `ENTRA_ADMIN_GROUPS` sai Object ID, hoặc bạn chưa ở trong nhóm đó.

**3. Vai trò đến từ nhóm, không phải từ cửa hậu** — xoá `ENTRA_BOOTSTRAP_ADMINS`,
restart, đăng nhập lại, `admin` vẫn phải là `true`.

> Bước này **không phải tuỳ chọn**. Còn cửa hậu thì ai chiếm được email đó là chiếm được
> quyền admin, bất kể nhóm Entra đã thu hồi hay chưa. Mỗi lần khởi động mà nó còn bật,
> log ghi `WARN` nhắc việc.

**4. Bot nhận được tin** — trong Teams, nhắn riêng cho bot. Phải thấy "đang gõ" rồi ra thẻ
trả lời kèm mục **Nguồn**.

**5. Bot không bịa** — hỏi câu chắc chắn không có trong tài liệu, phải trả lời "không tìm
thấy".

**6. Bot chặn đúng trong channel** — thêm bot vào một Team rồi @mention. Chưa bật
`channelAllowed` cho nhóm tài liệu nào thì bot phải **từ chối** kèm hướng dẫn nhắn riêng.
Đó là đúng, không phải lỗi.

**7. Phép thử quan trọng nhất** — nhờ một đồng nghiệp **khác phòng** hỏi **cùng một câu**
trong chat riêng. Kết quả phải **khác nhau**. Giống nhau nghĩa là ACL chưa có hiệu lực
thật — dừng lại, đừng mở rộng cho toàn công ty.

---

## 6. Chẩn đoán sự cố

| Hiện tượng | Nguyên nhân |
|---|---|
| Portal báo *Groups are not available… plan level* | Đúng như dự kiến ở gói Free. **Bỏ qua app role**, dùng `ENTRA_ADMIN_GROUPS` (mục 0.1). Không cần mua P1. |
| Đăng nhập được nhưng hỏi gì cũng "không tìm thấy tài liệu" | Chọn nhầm **Delegated** thay vì **Application permissions**, hoặc chưa **Grant admin consent** (1.4). Kiểm tra `entraGroups` trong `/api/v1/rag/me`: rỗng ⇒ Graph chết. |
| `entraGroups` có nhóm nhưng `departments` rỗng | Chưa tạo collection, hoặc `aclGroups` sai Object ID (4.3). |
| Người dùng bị chặn ngay sau khi chọn tài khoản | `Assignment required?` còn để **Yes** (1.5). |
| `AADSTS50011: redirect URI mismatch` | URI thực tế khác cái đã khai. Sai `http`/`https`, thừa `/` cuối, hoặc đang vào bằng IP `10.21.170.55:8080` thay vì qua hostname (4.4). |
| Trình duyệt cảnh báo cert khi vào `https://chatbot-uat2` | Hostname đó không có trong SAN của cert. Dùng `https://chatbot-uat.bsc.com.vn/rag/`. |
| `/rag/` trả 404 toàn bộ | `SERVER_CONTEXT_PATH` lệch với `ProxyPass` (mục 0.5). |
| Trang tải được nhưng mọi lời gọi API 404 | Có `fetch('/api/...')` tuyệt đối không bọc `url()` (mục 0.5). |
| `/rag/` trả về màn đăng nhập Drupal | `<Location /rag/>` bị xoá khỏi `chatbot.conf`, hoặc httpd chưa reload. |
| Câu trả lời hiện một lần cuối thay vì chảy dần | Thiếu `flushpackets=on` trên `ProxyPass`. |
| Câu trả lời bị cắt giữa dòng sau ~60s | Thiếu `timeout=300` trên `ProxyPass`. |
| Đăng nhập xong báo "Chỉ tài khoản bsc.com.vn…" | UPN ngoài `rag.entra.allowed-email-domains`. Tài khoản guest hay dính. |
| Vào `/admin.html` bị 403 | Không ở trong nhóm `ENTRA_ADMIN_GROUPS`, và cửa hậu đã gỡ. |
| Log `Graph: khong lay duoc nhom` | Sai secret / secret hết hạn / thiếu consent. Hệ thống **đóng quyền lại** — đúng thiết kế, đừng "sửa" thành mở. |
| Vừa thêm người vào nhóm mà chưa thấy tài liệu | Cache nhóm **15 phút** (`rag.entra.group-cache-minutes`). Đợi hoặc đăng xuất/đăng nhập lại. |
| Bot im lặng hoàn toàn, log trống | Chưa có `ProxyPass` (4.1), hoặc endpoint không ra được Internet. |
| Log `audience … khong khop app-id` | `BOT_APP_ID` không phải App ID của Azure Bot — nhiều khả năng điền nhầm App ID của App 1. |
| `Khong xin duoc token de goi Bot Framework` | Sai `BOT_APP_PASSWORD`, secret hết hạn, hoặc nhầm `BOT_APP_TYPE`. |
| Bot trả lời trùng nhiều lần | Bot Framework gửi lại vì endpoint chậm. Kiểm tra pool `teams-bot` có nghẽn không. |
| Ai hỏi cũng ra cùng kết quả | `rag.bot.unidentified-departments=*` còn bật, hoặc chưa có collection nào gắn ACL. |

---

## 7. Bảo trì

| Việc | Khi nào | Hậu quả nếu quên |
|---|---|---|
| Gia hạn `ENTRA_CLIENT_SECRET` | Trước ngày hết hạn ở mục 1.3 | Toàn hệ thống đọc được **0 tài liệu**, không có cảnh báo trên màn hình |
| Gia hạn `BOT_APP_PASSWORD` | Trước ngày hết hạn ở mục 2.3 | Bot nhận câu hỏi nhưng không trả lời được |
| Xoá `ENTRA_BOOTSTRAP_ADMINS` | Ngay sau nghiệm thu bước 5.3 | Cửa hậu admin tồn tại vĩnh viễn |
| Rà thành viên nhóm `RAG - Quản trị` | Định kỳ | Người chuyển bộ phận vẫn giữ quyền admin |
| Gia hạn cert `chatbot-uat.bsc.com.vn` | Trước **10/02/2027** | Giao diện web và bot ngừng hoạt động qua HTTPS |

Đặt lịch nhắc cho hai dòng đầu **ngay hôm nay**, không để sau.

---

## 8. Bảng bàn giao

Gửi lại cho người vận hành ứng dụng:

```
ENTRA_TENANT_ID      = <Directory (tenant) ID>              mục 1.2
ENTRA_CLIENT_ID      = <Application (client) ID>            mục 1.2
ENTRA_CLIENT_SECRET  = <Value của secret App 1>             mục 1.3
ENTRA_ADMIN_GROUPS   = <Object ID nhóm "RAG - Quản trị">    mục 1.6

BOT_APP_ID           = <Microsoft App ID>                   mục 2.3
BOT_APP_PASSWORD     = <Value của secret App 2>             mục 2.3

+ bảng Object ID của các nhóm phòng ban                     mục 1.6
```

**Hai secret gửi qua kênh bảo mật** — không dán vào chat, không gửi qua email.
