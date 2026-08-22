# Đưa bot Teams vào chạy thật — checklist nghiệm thu

Tài liệu này **không** lặp lại [TEAMS-BOT-SETUP.md](TEAMS-BOT-SETUP.md) (thao tác Azure) hay
[DEPLOY-UAT.md](DEPLOY-UAT.md) (Apache/systemd). Nó trả lời đúng một câu hỏi: *giao diện web
chat được rồi, còn thiếu gì để chat được trên Teams* — theo thứ tự phải làm, kèm điểm kiểm
tra để biết đã xong bước đó hay chưa.

## Chẩn đoán: phần Java đã đủ, thiếu 4 nhóm việc

| Nhóm | Trạng thái |
|---|---|
| Mã nguồn bot (`com.ai.aiagent.bot`) | ✅ Đủ — endpoint, xác thực token, gửi chiều ra, thẻ, phân quyền, nhiều bot |
| A. Đường mạng: Azure gọi được `/api/messages` từ Internet | ❌ Phải xác nhận trước mọi thứ khác |
| B. Azure Bot resource + Teams app đã phát hành | ❌ Việc của IT/Teams admin |
| C. Dữ liệu nền tảng (collection ⇄ bot ⇄ team) ở màn quản trị | ❌ Bot chạy đúng vẫn **từ chối trả lời** nếu thiếu |
| D. 4 khoảng trống nhỏ trong code (timeout, clock skew, độ dài thẻ, chẩn đoán) | ⚠️ Nên bịt trước khi mở cho cả công ty |

Thứ tự A → B → C là **bắt buộc**: làm B trước A thì bot cài xong vẫn im lặng tuyệt đối và
không có log nào để lần.

---

## Giai đoạn A — Đường mạng (chặn cứng, làm trước tiên)

Azure Bot Service là dịch vụ **ngoài Internet** gọi **vào** máy chủ. UAT `10.21.170.55` là
IP nội bộ; nếu `chatbot-uat.bsc.com.vn` chưa được publish ra Internet thì bot **không bao
giờ** chạy được, bất kể cấu hình đúng đến đâu.

### A1. Bật ứng dụng với bot ở chế độ tối thiểu

Trong `config/aiagent.env` — mọi thứ có biến môi trường đều khai ở đây, không sửa
`config/application.properties` (ngoại lệ duy nhất là map nhóm Entra ở B3):

```
BOT_ENABLED=true
BOT_APP_ID=<điền sau khi có ở Giai đoạn B>
BOT_APP_PASSWORD=<điền sau>
```

`systemctl restart aiagent`. Với `BOT_APP_ID` rỗng, endpoint vẫn tồn tại và trả 401 — đúng
là thứ cần để test đường mạng.

### A2. Test từ trong máy chủ

```bash
curl -i -X POST http://127.0.0.1:8080/rag/api/messages \
  -H 'Content-Type: application/json' -d '{"type":"message"}'
```

| Kết quả | Nghĩa |
|---|---|
| `401` | ✅ Endpoint sống, `BotAuthenticator` từ chối vì thiếu token — đúng như mong đợi |
| `404` | Sai tiền tố (`SERVER_CONTEXT_PATH`) **hoặc** `BOT_ENABLED` chưa bật (không có controller) |
| `400` | Body không phải JSON — sai cách gõ curl, không phải lỗi hệ thống |

### A3. Test từ ngoài mạng công ty (bước quyết định)

Chạy từ một máy **không** trong mạng BSC (4G, máy nhà):

```bash
curl -i -X POST https://chatbot-uat.bsc.com.vn/rag/api/messages \
  -H 'Content-Type: application/json' -d '{"type":"message"}'
```

| Kết quả | Việc phải làm |
|---|---|
| `401` | ✅ Xong Giai đoạn A, sang B |
| Timeout / không kết nối | Chưa publish ra Internet → xem A4 |
| `404` | `ProxyPass /rag/` chưa có hoặc sai — xem [DEPLOY-UAT.md](DEPLOY-UAT.md) Bước 5 |
| Lỗi TLS | Chứng thư GlobalSign hết hạn/thiếu chuỗi trung gian. Azure **từ chối** cert tự ký |

### A4. Nếu chưa public — hai đường đi

**Đường chính thức (để chạy thật):** đề nghị hạ tầng NAT/publish `chatbot-uat.bsc.com.vn`
qua DMZ, mở **chỉ** `443` vào Apache. Chỉ cần đường `/rag/api/messages`; không cần mở cả
`8080`. Đây là việc phải xin, có thể mất vài ngày — nên làm sớm, song song với B và C.

**Đường tạm (chỉ để kiểm thử, không dùng cho UAT thật):** chạy ứng dụng trên máy dev rồi
mở tunnel:

```powershell
winget install Microsoft.devtunnel
devtunnel user login
devtunnel host -p 8080 --allow-anonymous
# lấy URL https://<id>.devtunnels.ms → messaging endpoint là <URL>/api/messages
```

Máy dev không có `SERVER_CONTEXT_PATH` nên endpoint **không** có `/rag`. Nhớ đổi lại
messaging endpoint trên Azure khi chuyển sang UAT — quên bước này là nguyên nhân số một
của "hôm qua chạy được, hôm nay im".

> Tunnel là công cụ **kiểm thử**. Đừng để một địa chỉ `devtunnels.ms` làm endpoint chính
> thức: nó chết khi tắt máy dev, và mọi câu hỏi của người dùng đi qua máy cá nhân.

---

## Giai đoạn B — Azure Bot + Teams app

Hướng dẫn từng cú click cho chính máy chủ UAT này: [AZURE-SETUP-UAT.md](AZURE-SETUP-UAT.md)
mục 1–4 (hai app registration, quyền Graph, Azure Bot, đóng gói Teams app). Bản rút gọn không
gắn với UAT: [TEAMS-BOT-SETUP.md](TEAMS-BOT-SETUP.md) mục 1–3. Phần dưới chỉ là những chỗ
**đã từng sai** và điểm kiểm tra.

### B1. Azure Bot resource

- Messaging endpoint: `https://chatbot-uat.bsc.com.vn/rag/api/messages` — **có `/rag`**.
- Type of App: `Multi Tenant` nếu không có yêu cầu bảo mật khác. Chọn `Single Tenant` thì
  **bắt buộc** thêm `BOT_APP_TYPE=SINGLE_TENANT` + `BOT_TENANT_ID`, nếu không bot nhận được
  câu hỏi nhưng không gửi được trả lời (401 lúc xin token chiều ra).
- **Channels → Microsoft Teams → Apply.** Thiếu bước này Teams không chuyển tin nhắn nào.
- Client secret: ghi ngày hết hạn vào lịch **ngay hôm nay**. Secret hết hạn ⇒ bot im lặng,
  log `Khong xin duoc token de goi Bot Framework`.

### B2. Quyền Graph app-only — chỗ dễ quên nhất

Trên web, quyền suy từ token của người đăng nhập. Trên Teams, bot chỉ có `aadObjectId` nên
`BotAccessResolver` gọi Graph **app-only** (`POST /users/{id}/getMemberGroups`,
`client_credentials`). App registration của Entra phải có **Application permission**:

| Permission | Loại | Dùng để |
|---|---|---|
| `GroupMember.Read.All` | Application | Lấy nhóm Entra của người hỏi ⇒ suy phòng ban |
| `User.Read.All` | Application | Lấy UPN / phòng ban để ghi nhật ký |

Cả hai phải **Grant admin consent**. Không có consent ⇒ Graph trả rỗng ⇒ theo bất biến
"không lấy được nhóm = không có quyền nào", bot **từ chối mọi người** với thông báo về
quyền. Đó là hành vi đúng, và cũng chính là triệu chứng.

Kiểm tra: `ENTRA_GRAPH_ENABLED=true` và một người dùng bất kỳ mở
`https://chatbot-uat.bsc.com.vn/rag/api/v1/rag/me` sau khi đăng nhập — phải thấy danh sách
phòng ban khác rỗng.

### B3. Map nhóm Entra → phòng ban

```
rag.entra.group-departments[<group object id>]=nhan-su,ke-toan
```

Khai trong **`config/application.properties`**, KHÔNG phải `config/aiagent.env`: khoá là
một GUID có dấu gạch ngang và phải bọc trong `[...]`, mà `aiagent.env` là EnvironmentFile của
systemd nên tên biến không nhận được `[`, `]` hay `-`. Đây là ngoại lệ duy nhất được ghi giá
trị thật vào `application.properties` — đúng như chú thích đầu file: chỉ thêm khoá vào đó khi
jar không có biến môi trường tương ứng.

**Chưa map nhóm nào ⇒ mọi người đọc được 0 tài liệu ⇒ bot từ chối.** Đây là lỗi hay bị hiểu
nhầm thành "bot lỗi".

Muốn thử nhanh trước khi có map đầy đủ (chỉ khi **chưa** có tài liệu hạn chế nào):

```
BOT_UNIDENTIFIED_DEPARTMENTS=*
```

Xoá dòng này trước khi mở cho cả công ty.

### B4. Đóng gói Teams app

```powershell
cd teams-app
.\build.ps1 -BotAppId <Microsoft App ID> -AppHost chatbot-uat.bsc.com.vn
```

Đừng nén tay bằng `Compress-Archive`. Script chặn trước sáu lỗi mà Teams chỉ báo chung là
*"manifest không hợp lệ"*: zip bọc thư mục, manifest có BOM, còn placeholder, App ID không
phải GUID, icon sai kích thước, `outline.png` thiếu kênh alpha. Chi tiết:
[TEAMS-BOT-SETUP.md](TEAMS-BOT-SETUP.md) mục 2.

Manifest chỉ khai `"scopes": ["personal"]` — bot dùng ở **chat riêng**. Mở cho channel là
việc của Giai đoạn C2, cần làm cả hai thứ: thêm scope vào manifest **và** bật cờ *trả lời
trong kênh*. Thiếu cái thứ hai thì bot vẫn từ chối, đúng như thiết kế.

Teams Admin Center → Manage apps → Upload new app → **Permission policies** cho phép app,
gán cho nhóm thử nghiệm trước (5–10 người), chưa mở toàn công ty.

### Điểm kiểm tra Giai đoạn B

Cài app cho chính bạn (Teams → Apps → Built for your org). Phải thấy:

- Trong Teams: **thẻ chào** hiện ra ngay khi cài.
- Trong log máy chủ: `Bot: dung khoa ky tu https://login.botframework.com/...`

Không có thẻ chào nhưng log có dòng trên ⇒ lỗi **chiều ra**, tìm log `Bot: gui card that bai`.
Không có dòng log nào ⇒ quay lại Giai đoạn A.

---

## Giai đoạn C — Dữ liệu nền tảng ở màn quản trị

Đây là chỗ bot "chạy đúng mà vẫn không trả lời". Bot phân quyền bằng **giao của nhiều tập**,
tập nào rỗng thì kết quả rỗng.

### C0. Cách nhanh: chạy script một lần

[deploy/setup-bot-platform.ps1](../deploy/setup-bot-platform.ps1) làm đủ chuỗi
nhóm tài liệu → ACL → bot → gán nhóm → bot mặc định → gán Team, rồi in lại `readiness` của
`/bot-status`. Chạy lại được nhiều lần: cái gì đã có thì dùng lại, không tạo trùng.

```powershell
.\deploy\setup-bot-platform.ps1 `
  -BaseUrl https://chatbot-uat.bsc.com.vn/rag `
  -AdminApiKey $env:RAG_ADMIN_API_KEY `
  -CollectionSlug nhan-su -CollectionName "Tài liệu Nhân sự" `
  -AclGroupIds 8f4e1c2a-1111-2222-3333-444444444444 `
  -BotSlug tro-ly -BotName "Trợ lý tài liệu"
```

Hai tham số **cố ý phải khai tường minh**, vì cả hai đều nới rộng phạm vi đọc:
`-ChannelAllowed` (cho cả kênh Teams đọc nhóm tài liệu này) và `-OpenToEveryone` (xoá giới
hạn đối tượng dùng bot). Không truyền thì script **không sửa** hai thứ đó.

Muốn làm tay hoặc muốn hiểu từng bước thì đọc tiếp — màn `/rag/admin.html` tab
**Bot & phân quyền**, hoặc các endpoint trong `PlatformAdminController`.

### C1. Chat riêng chạy được — làm đủ 4 việc

1. **Ít nhất một collection đang hoạt động**, và tài liệu đã nạp vào đúng `category` =
   `slug` của collection đó (`rag_collections.slug` **chính là** cột `category`).
2. **ACL của collection** phải chứa nhóm Entra của người thử. ACL rỗng = **đóng**, không ai
   đọc được — cố ý như vậy.
3. **Một bot đang hoạt động, đã đặt làm bot mặc định**, và đã **gán collection** cho nó.
   Không có bot nào ⇒ *"Hệ thống chưa cấu hình trợ lý nào đang hoạt động."*
4. **Đối tượng sử dụng bot**: để **rỗng** = ai cũng dùng được (ngược với ACL collection —
   xem bất biến trong CLAUDE.md). Chỉ điền khi muốn giới hạn.

Kiểm tra: nhắn riêng cho bot một câu chắc chắn có trong tài liệu → thấy "đang gõ" rồi ra
thẻ trả lời kèm mục **Nguồn**.

Từ chối kèm lý do gì thì sửa đúng chỗ đó:

| Thông báo từ chối | Thiếu |
|---|---|
| "chưa cấu hình trợ lý nào" | C1.3 — chưa có bot active/mặc định |
| "chưa nằm trong nhóm được sử dụng trợ lý này" | C1.4 — audience đang chặn bạn |
| "chưa được cấp quyền đọc nhóm tài liệu nào" | C1.1/C1.2 hoặc B3 — giao của bot ∩ quyền bạn = rỗng |
| "Chưa xác định được tài khoản công ty" | B2 — Graph app-only chưa consent, hoặc Entra tắt |

### C2. Trả lời trong channel (làm sau, khi chat riêng đã ổn)

Phạm vi trong channel = `(collection của bot) ∩ (quyền người hỏi) ∩ (collection bật "trả
lời trong kênh")`, và **ADMIN bị hạ xuống USER**. Cần thêm:

1. Bật cờ **trả lời trong kênh** (`channel_allowed`) cho những collection **chấp nhận cho
   cả kênh đọc được**. Cân nhắc từng cái — câu trả lời hiện ra cho mọi thành viên channel.
2. Gán **Team → bot** (`rag_bot_channels`).

Chưa làm ⇒ bot từ chối trong channel và hướng dẫn nhắn riêng. Đó là mặc định an toàn có chủ
ý, **không phải lỗi**.

---

## Giai đoạn D — Bốn khoảng trống trong code: ĐÃ BỊT

Giữ lại mục này để biết *đã* sửa gì, đừng đi làm lại.

| # | Khoảng trống | Đã bịt ở |
|---|---|---|
| D1 | `connector-timeout-seconds` không được áp dụng ⇒ một lời gọi treo giữ vĩnh viễn một thread trong pool `teams-bot`, đủ 8 lần là bot ngừng trả lời không log gì | `HttpTimeouts.factory(...)` dùng trong cả `BotConnectorClient` và `BotAuthenticator` |
| D2 | `max-clock-skew-seconds` không được áp dụng (sai số cố định 60s của `JwtValidators.createDefault()`) | `BotAuthenticator` dùng `JwtTimestampValidator(skew)` |
| D3 | `max-answer-chars` không được áp dụng ⇒ câu trả lời dài làm **cả thẻ** không gửi được | `AdaptiveCards.answer(response, maxCitations, maxAnswerChars)` cắt bằng `clamp` |
| D4 | Không có đường chẩn đoán, phải SSH đọc biến môi trường | `GET /api/v1/rag/admin/bot-status` (`BotDiagnosticsController`, chỉ ADMIN) |

Vẫn còn cố ý để lại: **chống trùng theo `activity.id`**. Rủi ro thấp vì controller trả `200`
ngay trước khi xử lý; chỉ làm nếu thấy trả lời lặp.

### Dùng `/bot-status` khi bot im lặng

```bash
curl -s -H "X-API-Key: $RAG_ADMIN_API_KEY"   'http://127.0.0.1:8080/rag/api/v1/rag/admin/bot-status?probeToken=true' | jq
```

Trường `readiness` liệt kê **theo thứ tự** những thứ khiến bot từ chối hoặc im lặng — rỗng
là không còn vướng gì ở phía ứng dụng. `probeToken=true` xin thật một token chiều ra, tức là
kiểm luôn `BOT_APP_PASSWORD` và `BOT_APP_TYPE`; để mặc định `false` cho lượt giám sát định kỳ
vì nó gọi ra Microsoft.

## Giai đoạn E — Bộ kiểm thử nghiệm thu

Chỉ ghi ✅ khi có bằng chứng: ảnh chụp Teams hoặc dòng log tương ứng.

| # | Kiểm thử | Kết quả đúng |
|---|---|---|
| 1 | Cài app | Thẻ chào hiện ra |
| 2 | `hướng dẫn` trong chat riêng | Thẻ chào (lời chào riêng của bot nếu đã đặt) |
| 3 | Câu hỏi có trong tài liệu | "đang gõ" → thẻ trả lời + mục **Nguồn** đúng file |
| 4 | Câu hỏi không có trong tài liệu | Nói rõ không tìm thấy, **không bịa**, không có mục Nguồn |
| 5 | Đồng nghiệp **khác phòng** hỏi cùng câu | Kết quả **khác nhau**. Giống nhau ⇒ ACL chưa có hiệu lực — dừng, không mở rộng |
| 6 | @mention trong channel khi chưa bật C2 | Từ chối + hướng dẫn nhắn riêng |
| 7 | @mention sau khi bật C2 | Chỉ trả lời từ collection đã bật "trả lời trong kênh" |
| 8 | ADMIN @mention trong channel | **Không** kéo được tài liệu hạn chế (ADMIN hạ xuống USER) |
| 9 | Gửi 15 câu trong 1 phút | Sau câu thứ 12: "Bạn đang hỏi hơi nhanh" |
| 10 | Câu trả lời rất dài | Vẫn ra thẻ (chỉ đúng sau khi bịt D3) |

Kiểm thử #5 là quan trọng nhất. #8 là kiểm thử rò rỉ dữ liệu — bắt buộc trước khi bot vào
bất kỳ channel nào.

---

## Giai đoạn F — Vận hành sau khi mở

- **Số liệu**: `/actuator/prometheus`, mọi chuỗi `rag.latency` có nhãn `bot` (đường web =
  `web`). Theo dõi tỉ lệ từ chối trả lời theo bot — tăng vọt thường là lỗi phân quyền, không
  phải lỗi mô hình.
- **Nhật ký kiểm toán**: `AuditFilter` ghi tự động, kể cả request bị 401/403. Chỉ có đường
  đọc.
- **Vòng đời secret**: client secret của Azure Bot và của Entra app — đặt nhắc trước 30 ngày.
- **Câu hỏi thật**: sau 2–3 tuần chạy, `POST /eval/cases/harvest` để lập bộ đo hồi quy, và
  `harvest {"negative":true}` để xem những câu bị 👎.
- **Mở rộng thêm bot phòng ban**: tạo bot ở màn quản trị + gán collection + gán Team. Không
  cần IT tạo app registration mới, không cần duyệt Teams app mới. Chỉ khi cần **tên và
  avatar riêng** trong Teams mới phải làm Azure Bot + Teams app riêng.

---

## Đường tới hạn

Giai đoạn A4 (xin publish ra Internet) là việc phải chờ người khác và có thể mất vài ngày.
B, C, D làm song song được ngay hôm nay; A4 xong là nghiệm thu được luôn.
