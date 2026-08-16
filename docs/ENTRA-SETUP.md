# Cấu hình đăng nhập bằng tài khoản công ty (Microsoft Entra ID)

Tài liệu thao tác cho quản trị viên. Thiết kế và lý do của từng lựa chọn nằm ở
[BOT-PLATFORM.md](BOT-PLATFORM.md) mục 3 và 4.

> **Mặc định TẮT.** Không đặt `ENTRA_ENABLED=true` thì hệ thống chạy y như trước,
> xác thực bằng API key. Bật SSO là một công tắc, không phải một bản rẽ nhánh.

---

## 1. Việc của IT — app registration

Azure Portal → **Microsoft Entra ID** → **App registrations** → **New registration**.

| Mục | Giá trị |
|---|---|
| Name | `BSC RAG Assistant` |
| Supported account types | **Single tenant** (chỉ tổ chức này) |
| Redirect URI | Web → `https://<host>/login/oauth2/code/entra` |

`<host>` là địa chỉ thật người dùng gõ. Chạy thử trên máy cá nhân thì thêm cả
`http://localhost:8080/login/oauth2/code/entra`.

**Single tenant là lớp bảo vệ thứ nhất** — tài khoản ngoài tổ chức không đăng nhập được
ngay từ Microsoft. Ứng dụng còn kiểm tra claim `tid` và đuôi email lần nữa; giữ cả hai
là cố ý, vì đổi app registration sang multi-tenant chỉ là một dòng cấu hình còn sửa
lớp thứ hai thì phải sửa code.

### 1.1 Credential

**Certificates & secrets** → tạo credential.

> Ưu tiên **Certificate**. Client secret hết hạn là hệ thống chết im lặng — đây là
> nguyên nhân sự cố phổ biến nhất của tích hợp Entra. Nếu buộc dùng secret thì chọn hạn
> dài nhất được phép và **đặt lịch nhắc gia hạn ngay hôm nay**.

### 1.2 Quyền Microsoft Graph

**API permissions** → Add a permission → Microsoft Graph → **Application permissions**:

| Quyền | Dùng để làm gì |
|---|---|
| `User.Read.All` | đọc hồ sơ người dùng (tên, phòng ban) để hiển thị và audit |
| `GroupMember.Read.All` | đọc nhóm của người dùng — **đây là cơ sở phân quyền tài liệu** |

Rồi bấm **Grant admin consent**. Không có consent thì mọi người đăng nhập được nhưng
không thuộc nhóm nào, và theo nguyên tắc mặc-định-từ-chối sẽ **không đọc được tài liệu nào**.

### 1.3 App role

**App roles** → Create app role, tạo ba role (value phải đúng chính tả):

| Display name | Value | Allowed member types | Ai được gán |
|---|---|---|---|
| RAG Admin | `RagAdmin` | Users/Groups | nhóm quản trị hệ thống |
| RAG Editor | `RagEditor` | Users/Groups | cán bộ được upload tài liệu |
| RAG User | `RagUser` | Users/Groups | toàn thể CBNV |

Gán ở **Enterprise applications** → ứng dụng vừa tạo → **Users and groups** →
Add user/group. **Gán cho NHÓM, không gán cho từng người** — người vào/ra công ty thì
nhóm tự cập nhật, danh sách cá nhân thì không.

### 1.4 Thông tin cần bàn giao

- **Directory (tenant) ID**
- **Application (client) ID**
- **Client secret** (hoặc certificate) — gửi qua kênh bảo mật, không qua chat/email
- **Object ID của các nhóm phòng ban** dùng làm ACL tài liệu

---

## 2. Việc của người vận hành ứng dụng — biến môi trường

```powershell
$env:ENTRA_ENABLED       = 'true'
$env:ENTRA_TENANT_ID     = '<Directory (tenant) ID>'
$env:ENTRA_CLIENT_ID     = '<Application (client) ID>'
$env:ENTRA_CLIENT_SECRET = '<client secret>'

# Cửa hậu khởi động — xem mục 3
$env:ENTRA_BOOTSTRAP_ADMINS = 'quangbd@bsc.com.vn'
```

Thiếu `ENTRA_TENANT_ID` hoặc `ENTRA_CLIENT_ID` mà vẫn bật `ENTRA_ENABLED=true` thì
ứng dụng **từ chối khởi động** kèm thông báo rõ, thay vì âm thầm chạy tiếp bằng API key.

### 2.1 Nhóm Entra → phòng ban

Object ID của nhóm có dấu gạch ngang nên **bắt buộc dùng cú pháp ngoặc vuông** trong
`application.properties`:

```properties
rag.entra.group-departments[8f4e1c2a-1111-2222-3333-444444444444]=nhan-su
rag.entra.group-departments[9a5b2d3e-5555-6666-7777-888888888888]=ke-toan,cong-no
```

Giá trị bên phải phải khớp cột `category` của tài liệu đã nạp. Xem danh sách category
hiện có ở màn quản trị, tab **Tài liệu**.

**Người không khớp nhóm nào và không phải ADMIN thì không đọc được tài liệu nào.**
Đây là chủ ý — người mới vào công ty chưa được gán nhóm sẽ không vô tình đọc được
toàn bộ tài liệu nội bộ. Giai đoạn chạy thử, nếu muốn mở tạm:

```properties
rag.entra.fallback-departments=*
```

Nhớ gỡ trước khi chạy thật.

---

## 3. Thứ tự bật, để không tự khoá mình ra ngoài

Có một vòng luẩn quẩn: muốn cấu hình thì phải vào `/admin.html`, muốn vào `/admin.html`
thì phải có app role `RagAdmin`, mà app role lại do IT gán. `ENTRA_BOOTSTRAP_ADMINS`
tồn tại để phá vòng đó.

1. Bật `ENTRA_ENABLED=true` **kèm** `ENTRA_BOOTSTRAP_ADMINS=<email của bạn>`.
2. Đăng nhập, xác nhận vào được `/admin.html`.
3. Nhờ IT gán app role `RagAdmin` cho nhóm quản trị.
4. Đăng xuất, đăng nhập lại, xác nhận vẫn vào được **mà không cần** cửa hậu.
5. **Xoá `ENTRA_BOOTSTRAP_ADMINS`** rồi khởi động lại.

Bước 5 không phải tuỳ chọn. Còn cấu hình đó thì ai chiếm được email đó là chiếm được
quyền admin, bất kể IT đã thu hồi app role hay chưa. Mỗi lần khởi động mà cửa hậu còn
bật, log sẽ ghi `WARN` nhắc việc này.

---

## 4. Kiểm tra sau khi bật

```bash
# 1. Hệ thống báo đã bật SSO chưa
curl -s https://<host>/api/v1/rag/me
# => {"ssoEnabled":true,"loginUrl":"/oauth2/authorization/entra","authenticated":false}

# 2. Trang web phải đẩy sang Microsoft
curl -s -o /dev/null -w '%{http_code} %{redirect_url}\n' https://<host>/admin.html
# => 302 .../oauth2/authorization/entra

# 3. API không kèm gì phải trả 401 JSON, KHÔNG được trả 302
curl -s https://<host>/api/v1/rag/admin/documents
# => {"error":"Chua dang nhap hoac phien da het han.","loginUrl":"...","status":401}

# 4. API key VẪN phải chạy song song (dành cho webhook/script)
curl -s -o /dev/null -w '%{http_code}\n' \
     -H "X-API-Key: $RAG_ADMIN_API_KEY" https://<host>/api/v1/rag/admin/documents
# => 200
```

Sau khi đăng nhập bằng trình duyệt, mở lại `/api/v1/rag/me` — phản hồi cho biết hệ
thống nhìn thấy bạn thuộc nhóm nào và đọc được phòng ban nào. Đây là công cụ chẩn đoán
đầu tiên khi có người báo "tôi không xem được tài liệu X".

---

## 5. Chẩn đoán sự cố

| Hiện tượng | Nguyên nhân thường gặp |
|---|---|
| `AADSTS50011: redirect URI mismatch` | Redirect URI trong app registration khác `https://<host>/login/oauth2/code/entra`. Sai cả `http`/`https` hay thừa `/` cuối cũng lỗi. |
| Đăng nhập xong báo "Chỉ tài khoản bsc.com.vn…" | UPN không thuộc mien trong `rag.entra.allowed-email-domains`. Tài khoản khách (guest) hay bị dính. |
| Đăng nhập được nhưng hỏi gì cũng "không tìm thấy tài liệu" | Chưa map nhóm → phòng ban, hoặc chưa **Grant admin consent** cho Graph. Kiểm tra `/api/v1/rag/me`: `entraGroups` rỗng ⇒ Graph chưa chạy; có nhóm nhưng `departments` rỗng ⇒ thiếu `group-departments`. |
| Vào `/admin.html` bị 403 | Chưa được gán app role `RagAdmin`, và cửa hậu bootstrap đã gỡ. |
| Log `Graph: khong lay duoc nhom` | Sai secret, secret hết hạn, hoặc thiếu admin consent. Hệ thống **đóng quyền lại** chứ không mở — đúng thiết kế. |
| Người vừa được thêm vào nhóm vẫn chưa thấy tài liệu | Cache nhóm 15 phút (`rag.entra.group-cache-minutes`). Đợi, hoặc đăng xuất/đăng nhập lại. |

---

## 6. Những gì P1 CHƯA làm

Nói rõ để không ai tưởng đã xong:

- **Bot Teams vẫn dùng Outgoing Webhook** và vẫn cấp quyền đọc mọi phòng ban cho mọi
  người dùng Teams ([TeamsWebhookController.java:88](../src/main/java/com/ai/aiagent/chat/TeamsWebhookController.java#L88)).
  Phân quyền theo người trên Teams là **P2**, cần bot Bot Framework thật.
- **Phân quyền vẫn ở mức phòng ban** (cột `category`), chưa có collection, chưa có
  nhiều bot, chưa có quyền theo từng bot/từng tập tài liệu — đó là **P3**.
- **Chưa có vai trò Editor thật.** `RagEditor` hiện chỉ được cấp `USER`; upload tài liệu
  vẫn đòi `RagAdmin`. Phân quyền mịn theo collection cũng thuộc P3.
