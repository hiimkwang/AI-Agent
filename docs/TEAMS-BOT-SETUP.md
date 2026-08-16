# Cài bot Teams (Azure Bot Service)

Tài liệu thao tác. Lý do chọn hướng này thay vì Outgoing Webhook hay Copilot Studio:
[BOT-PLATFORM.md](BOT-PLATFORM.md) mục 1.

> **Mặc định TẮT.** Không đặt `BOT_ENABLED=true` thì không có endpoint `/api/messages`,
> không có bean nào của bot được tạo, hệ thống chạy y như trước.

Yêu cầu trước: đã bật đăng nhập Entra ID theo [ENTRA-SETUP.md](ENTRA-SETUP.md). Bot dùng
lại đúng `EntraScopeService` của giao diện web để suy quyền, nên không có Entra thì bot
không phân quyền theo người được — nó sẽ từ chối trả lời (xem mục 4).

---

## 1. Việc của IT — Azure Bot resource

Azure Portal → **Create a resource** → **Azure Bot**.

| Mục | Giá trị |
|---|---|
| Bot handle | `bsc-rag-assistant` |
| Type of App | **Multi Tenant** (mặc định, đơn giản nhất) |
| Creation type | Create new Microsoft App ID |

Sau khi tạo:

1. **Configuration** → **Messaging endpoint**: `https://<host>/api/messages`
   `<host>` phải là **HTTPS công khai** mà Azure gọi được từ Internet.
2. **Configuration** → **Microsoft App ID**: ghi lại → `BOT_APP_ID`.
3. **Manage Password** → **New client secret** → ghi lại → `BOT_APP_PASSWORD`.
4. **Channels** → **Microsoft Teams** → Apply.

### Về `Multi Tenant` vs `Single Tenant`

Multi Tenant là mặc định của Azure Bot và đơn giản hơn. Nếu chính sách bảo mật yêu cầu
Single Tenant thì thêm:

```
BOT_APP_TYPE=SINGLE_TENANT
BOT_TENANT_ID=<Directory (tenant) ID>
```

Hệ thống tự đổi cả **issuer** được chấp nhận lẫn **tenant xin token gọi ra**. Đây là chỗ
hay sai nhất: bot multi-tenant xin token ở tenant **ảo** `botframework.com` chứ không phải
tenant công ty, và triệu chứng khi nhầm là lỗi 401 khó hiểu lúc gửi tin nhắn — bot nhận
được câu hỏi nhưng không trả lời được.

---

## 2. Đóng gói Teams app

Thư mục [teams-app/](../teams-app/) có sẵn `manifest.json`. Cần làm:

1. Thay `{{BOT_APP_ID}}` bằng Microsoft App ID, `{{APP_HOST}}` bằng host của bạn
   (chỉ tên miền, không có `https://`).
2. Thêm hai icon cạnh manifest:
   - `color.png` — **192×192**, nền đầy màu
   - `outline.png` — **32×32**, chỉ trắng trên nền trong suốt
3. Nén **ba file phẳng** (không bọc thư mục) thành `bsc-rag-assistant.zip`:

```powershell
cd teams-app
Compress-Archive -Path manifest.json,color.png,outline.png -DestinationPath ..\bsc-rag-assistant.zip -Force
```

> Nén cả thư mục thay vì ba file phẳng là lỗi phổ biến nhất khi upload — Teams báo
> "manifest không hợp lệ" mà không nói lý do thật.

4. **Teams Admin Center** → **Teams apps** → **Manage apps** → **Upload new app** →
   chọn file zip.

### Ai được dùng bot

**Teams admin center** → **Teams apps** → **Permission policies**. Tạo policy cho phép
app này rồi gán cho nhóm người dùng. Đây là lớp kiểm soát thứ nhất — nó quyết định ai
*thấy và cài* được bot.

Nhưng đừng dựa vào nó để bảo vệ dữ liệu: lớp thật sự bảo vệ là ACL tài liệu trong câu SQL
(mục 4), vì nó đúng kể cả khi bot bị cài sai chỗ.

---

## 3. Bật ở phía ứng dụng

```powershell
$env:BOT_ENABLED      = 'true'
$env:BOT_APP_ID       = '<Microsoft App ID>'
$env:BOT_APP_PASSWORD = '<client secret>'
# Single tenant thì thêm:
# $env:BOT_APP_TYPE  = 'SINGLE_TENANT'
# $env:BOT_TENANT_ID = '<tenant id>'
```

Khởi động lại. Log phải có dòng `Bot: dung khoa ky tu https://login.botframework.com/...`
ở lần nhận tin nhắn đầu tiên.

---

## 4. Phạm vi trả lời — phần phải hiểu trước khi mở cho cả công ty

### Chat riêng

Người hỏi là người **duy nhất** đọc câu trả lời, nên bot dùng đúng quyền cá nhân của họ:
nhóm Entra → phòng ban, hệt như trên giao diện web.

Chưa được cấp phòng ban nào ⇒ bot từ chối và **nói rõ là do quyền**, không nói "không tìm
thấy tài liệu" (người dùng sẽ hỏi lại mãi mà không biết phải làm gì).

### Channel — chỗ dễ rò rỉ nhất

Câu trả lời trong channel hiện ra cho **mọi thành viên channel**. Nếu bot dùng quyền của
người hỏi, một cán bộ Nhân sự @mention bot trong channel công khai sẽ **phát tán tài liệu
Nhân sự cho cả channel** — trong khi bot làm hoàn toàn đúng ACL của người hỏi.

Nên trong channel, phạm vi là **giao của ba tập**, chặt hơn bất kỳ tập nào:

```
(nhóm tài liệu bot được gán) ∩ (nhóm người hỏi đọc được) ∩ (nhóm bật "trả lời trong kênh")
```

Cấu hình ở màn **Quản trị → Bot & phân quyền**, không phải trong `application.properties`:
mỗi nhóm tài liệu có cờ **trả lời trong kênh** (`rag_collections.channel_allowed`), và mỗi
Team được gán một bot (`rag_bot_channels`).

Chưa bật cờ đó cho nhóm nào ⇒ bot **từ chối trả lời trong channel** và hướng dẫn người dùng
nhắn riêng. Đây là mặc định có chủ ý: an toàn, và người dùng vẫn có đường dùng được ngay.

Ngoài ra trong channel, quyền **ADMIN bị hạ xuống USER**. Chỉ thu hẹp phòng ban là chưa
đủ: `HybridRetriever` lọc `allowed_roles` bằng `isAdmin() ? Set.of() : roles()`, tức là
ADMIN bỏ qua ACL mức tài liệu — một quản trị viên hỏi trong channel sẽ kéo tài liệu hạn
chế ra cho cả kênh.

### Khi không xác định được người dùng

Thiếu `aadObjectId`, hoặc chưa bật `rag.entra.enabled` ⇒ bot **từ chối**. Một bot không
biết người hỏi là ai thì không thể thực thi ACL.

Muốn chạy thử khi mọi tài liệu đều công khai trong nội bộ:

```properties
rag.bot.unidentified-departments=*
```

Đây chính là hành vi cũ của Outgoing Webhook. Đừng để nguyên khi đã có tài liệu hạn chế.

---

## 5. Kiểm tra

1. Cài app cho **chính bạn** (Teams → Apps → Built for your org). Bot phải gửi thẻ chào.
2. Nhắn riêng một câu hỏi có trong tài liệu → phải thấy "đang gõ" rồi ra thẻ trả lời
   kèm mục **Nguồn**.
3. Hỏi câu chắc chắn không có trong tài liệu → phải trả lời "không tìm thấy", **không
   được bịa**.
4. Thêm bot vào một Team, @mention trong channel → nếu chưa khai `channel-departments`
   thì phải nhận lời từ chối kèm hướng dẫn nhắn riêng. Đó là đúng.
5. Nhờ một đồng nghiệp **khác phòng** hỏi cùng câu hỏi trong chat riêng — kết quả phải
   khác nhau theo quyền. Đây là phép thử quan trọng nhất; nếu giống nhau thì ACL chưa
   thực sự có hiệu lực.

---

## 6. Chẩn đoán sự cố

| Hiện tượng | Nguyên nhân thường gặp |
|---|---|
| Bot không phản hồi gì, log không có gì | Messaging endpoint sai, hoặc `<host>` không truy cập được từ Internet. Kiểm tra reverse proxy/firewall. |
| Log `Bot: token khong hop le` | Đồng hồ máy chủ lệch, hoặc endpoint đang bị chặn không lấy được khoá ký. |
| Log `audience ... khong khop app-id` | `BOT_APP_ID` khác App ID của Azure Bot resource. |
| Log `claim serviceurl khong khop` | Có ai đó gửi token của bot khác tới endpoint. Đúng ra phải từ chối — không được nới lỏng. |
| `Khong xin duoc token de goi Bot Framework` | Sai `BOT_APP_PASSWORD`, secret hết hạn, hoặc nhầm `BOT_APP_TYPE` (xem mục 1). |
| Bot nhận câu hỏi nhưng im lặng | Thường là lỗi gửi chiều ra. Tìm log `Bot: gui card that bai`. |
| Trả lời trùng nhau nhiều lần | Endpoint phản hồi chậm nên Bot Framework gửi lại. Không được xử lý RAG trong request gốc — hiện đã chạy nền, nếu tái diễn thì kiểm tra pool `teams-bot` có bị nghẽn không. |
| Ai hỏi cũng ra cùng kết quả | `rag.bot.unidentified-departments=*` còn bật, hoặc chưa map nhóm Entra → phòng ban. |

---

## 7. Nhiều bot

Một Azure Bot resource phục vụ được nhiều bot logic. Bot nào trả lời được suy ra từ
**nơi tin nhắn đến**, theo thứ tự ưu tiên:

1. `teams_app_id` của bot khớp `activity.recipient.id` — dùng khi bot đó có Azure Bot riêng
2. Ràng buộc đúng kênh trong `rag_bot_channels`
3. Ràng buộc cả Team trong `rag_bot_channels`
4. Bot mặc định

Thêm bot cho một phòng ban = tạo bot ở màn quản trị, gán nhóm tài liệu, rồi gán Team của
phòng đó. **Không cần IT tạo app registration mới, không cần duyệt Teams app mới.**

Mỗi bot có giọng điệu riêng (`persona_prompt`), lời chào riêng và model riêng. Giọng điệu
được chèn **trước** các quy tắc bắt buộc trong system prompt, không phải sau — đặt sau thì
một dòng cấu hình có thể vô hiệu hoá quy tắc "chỉ trả lời theo tài liệu", tức là người tạo
bot vô tình gỡ mất lớp chống bịa đặt.

Cần bot có **tên và avatar riêng** trong Teams thì phải tạo Azure Bot + Teams app riêng
(mục 1–2), rồi điền App ID đó vào ô *Teams app id riêng* của bot.

---

## 8. Những gì CHƯA làm

- Chưa có tin nhắn chủ động (thông báo, nhắc việc) và chưa có tool calling — bot mới chỉ
  tra cứu, chưa "làm việc".
- Vai trò Editor theo từng nhóm tài liệu (`rag_grants`) đã có bảng nhưng giao diện chưa
  tách: hiện vẫn cần `RagAdmin` để vào màn quản trị.
- Endpoint Outgoing Webhook cũ (`/api/v1/rag/teams-webhook`) **vẫn còn** và vẫn cấp
  quyền đọc mọi phòng ban. Sau khi bot mới chạy ổn, đặt `RAG_TEAMS_ENABLED=false` để
  tắt hẳn.
