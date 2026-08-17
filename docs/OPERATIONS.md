# Vận hành

Tài liệu cho người chạy hệ thống ở môi trường thật. Kiến trúc nằm ở
[ARCHITECTURE.md](ARCHITECTURE.md); đây là phần *giữ cho nó sống và giải trình được*.

---

## 1. Triển khai

Ba cách, chọn một:

| Cách | Khi nào dùng | Lệnh |
|---|---|---|
| systemd + jar | máy chủ UAT/PROD của công ty | [deploy/aiagent.service](../deploy/aiagent.service) |
| Docker Compose | máy chủ đã có Docker | `docker compose --profile app up -d --build` |
| `./mvnw spring-boot:run` | chỉ để phát triển | — |

### systemd

```bash
sudo useradd --system --home /opt/aiagent --shell /usr/sbin/nologin aiagent
sudo mkdir -p /opt/aiagent/logs /var/backups/aiagent
sudo cp target/AIAgent-*.jar /opt/aiagent/app.jar
sudo cp -r deploy /opt/aiagent/
sudo cp deploy/aiagent.service /etc/systemd/system/
sudo cp deploy/aiagent.env.example /etc/aiagent.env
sudo chmod 600 /etc/aiagent.env          # chứa secret
sudo chown -R aiagent:aiagent /opt/aiagent /var/backups/aiagent /etc/aiagent.env
sudo nano /etc/aiagent.env               # điền giá trị thật
sudo systemctl daemon-reload && sudo systemctl enable --now aiagent
journalctl -u aiagent -f
```

### Đứng sau reverse proxy

Hai cấu hình **bắt buộc**, thiếu là hỏng đăng nhập:

```properties
server.forward-headers-strategy=NATIVE   # FORWARD_HEADERS=NATIVE
rag.audit.trust-forwarded-for=true       # RAG_TRUST_PROXY=true
```

Thiếu dòng đầu, Spring sinh `redirect_uri` là `http://` và Microsoft trả
`AADSTS50011` dù cấu hình nginx đã đúng. Thiếu dòng sau, nhật ký kiểm toán ghi IP
của proxy thay vì IP người dùng.

Phía nginx, hai thứ hay quên:

```nginx
proxy_read_timeout 120s;   # khớp server.tomcat.connection-timeout
proxy_buffering off;       # cho /api/v1/rag/chat/stream (SSE)
```

---

## 2. Sao lưu và khôi phục

**Mất CSDL không chỉ mất lịch sử** — mất toàn bộ vector đã nhúng (phải *trả tiền*
nhúng lại cả kho) và mất nhật ký kiểm toán.

```bash
# Sao lưu ngay
/opt/aiagent/deploy/backup.sh

# Theo lịch — crontab của user aiagent
15 2 * * * /opt/aiagent/deploy/backup.sh >> /var/log/aiagent-backup.log 2>&1

# Khôi phục (DỪNG ứng dụng trước)
sudo systemctl stop aiagent
/opt/aiagent/deploy/restore.sh /var/backups/aiagent/rag_db-20260817-021500.dump
sudo systemctl start aiagent
```

`backup.sh` từ chối một bản dump nhỏ bất thường và tự đọc lại mục lục để phát hiện
file hỏng — vì một bản sao lưu chưa bao giờ được đọc thử thì không phải bản sao lưu.

**Sau khi khôi phục, kiểm tra ngay số chiều vector:**

```bash
curl -H "X-API-Key: $RAG_ADMIN_API_KEY" http://localhost:8080/api/v1/rag/admin/stats
```

`embeddingDimensions` lệch `rag.embedding.dimensions` ⇒ tìm kiếm sai **mà không báo
lỗi**. Phải nạp lại toàn bộ tài liệu.

**Ít nhất một lần mỗi quý, khôi phục thử sang một CSDL khác** rồi đối chiếu số tài
liệu/chunk. Chưa diễn tập thì chưa biết mình có khôi phục được không.

---

## 3. Nhật ký thao tác (audit trail)

Mọi request **làm thay đổi** dữ liệu/cấu hình dưới `/admin`, `/settings`, `/eval`
đều được ghi tự động — kể cả request **bị từ chối** (401/403).

Ghi bằng filter ([AuditFilter](../src/main/java/com/ai/aiagent/audit/AuditFilter.java))
chứ không phải lời gọi rải trong từng controller: một endpoint quản trị thêm sau này
được ghi mà tác giả không phải làm gì. Cách cũ chắc chắn sẽ có chỗ bị quên, và chỗ bị
quên chính là chỗ cần nhất.

```bash
# Ai đã làm gì, 30 ngày gần đây
GET /api/v1/rag/admin/audit?days=30&limit=100

# Chỉ các thao tác bị từ chối — câu hỏi đầu tiên khi nghi ngờ sự cố bảo mật
GET /api/v1/rag/admin/audit?deniedOnly=true&days=90

# Một người cụ thể
GET /api/v1/rag/admin/audit?actor=quangbd

# Tổng hợp
GET /api/v1/rag/admin/audit/summary?days=30
```

Hoặc dùng tab **Nhật ký** ở `/admin.html`.

Ba điều cần biết:

- **Chỉ có đường đọc.** Nhật ký mà sửa/xoá được qua API thì không còn là bằng chứng.
- **Giá trị của trường có tên gợi ý là secret bị che** (`key`, `secret`, `password`,
  `token`, `pat`). Nhật ký bị đọc bởi nhiều người hơn số người được biết secret.
- **Thân request multipart không được ghi** — nội dung file có thể 100MB; đọc vào bộ
  nhớ để ghi nhật ký là đổi một tính năng kiểm soát lấy một sự cố OOM.

Không ghi lượt đọc (GET) theo mặc định. Bật `rag.audit.include-read=true` nếu quy định
nội bộ yêu cầu chứng minh "ai đã xem gì" — chấp nhận nhật ký phình lên hàng chục lần.

---

## 4. Vòng đời dữ liệu

Chạy tự động hằng ngày lúc `rag.retention.run-at-hour` (mặc định 2 giờ sáng).

| Nhóm | Mặc định | Vì sao |
|---|---|---|
| Hội thoại | 180 ngày | dữ liệu cá nhân, giữ lâu không lợi ích gì |
| Nhật ký kiểm toán | 730 ngày | để giải trình, không phải dữ liệu vận hành |
| Job nạp liệu | 90 ngày | chỉ để tra cứu sự cố gần đây |

Đặt `<= 0` cho nhóm nào thì nhóm đó giữ vĩnh viễn.

```bash
GET  /api/v1/rag/admin/retention        # xem chính sách + lần chạy gần nhất
POST /api/v1/rag/admin/retention/run    # chạy ngay, để kiểm chứng
```

Không đụng tới `rag_documents`/`rag_chunks`: tài liệu hết hiệu lực đã bị lọc lúc truy
xuất (`rag.retrieval.exclude-expired`), còn xoá tự động tài liệu gốc là thứ không bao
giờ nên làm ngầm.

---

## 5. Quét virus file nạp vào

Mặc định **tắt**. Bật ở môi trường thật:

```properties
rag.antivirus.enabled=true
rag.antivirus.host=127.0.0.1
rag.antivirus.port=3310
rag.antivirus.fail-closed=true
```

Cài clamd:

```bash
sudo apt-get install clamav-daemon
sudo sed -i 's/^#TCPSocket/TCPSocket/' /etc/clamav/clamd.conf
sudo systemctl restart clamav-daemon
```

Quét đặt ở `IngestionService.ingest` — điểm nghẽn duy nhất của cả ba đường nạp
(`/upload`, `/upload-batch`, `/ingest-folder`), nên đường nạp thêm sau này không bị
bỏ sót.

`fail-closed=true` nghĩa là **không kết nối được clamd thì từ chối nạp file**. Đây là
lựa chọn có chủ ý, cùng nguyên tắc với `EntraScopeService` khi Graph lỗi: một bộ quét
virus tự động bỏ qua khi nó chết là một bộ quét virus không tồn tại.

---

## 6. OCR cho PDF bản scan

Mặc định **tắt**. Không bật thì PDF bản scan **không nạp được** — job tính là thất bại
kèm cảnh báo, không im lặng bỏ qua.

```properties
rag.ocr.enabled=true
rag.ocr.provider=OPENAI          # hoặc ANTHROPIC
rag.ocr.model=gpt-4o-mini
rag.ocr.max-pages=60
```

Cách làm: PDFBox kết xuất từng trang thành ảnh PNG rồi nhờ model thị giác chép lại
thành Markdown. Không thêm phụ thuộc nào — PDFBox đã có sẵn, model dùng lại API key
đã cấu hình. So với Tesseract: không cần cài gói nhị phân trên máy chủ (thứ môi trường
nội bộ thường không cho), chất lượng tiếng Việt có dấu tốt hơn đáng kể, và bảng biểu
được giữ dạng bảng Markdown thay vì vỡ vụn.

**Cái giá: một lời gọi model cho mỗi trang.** Vì vậy có trần `max-pages` — một bản scan
800 trang lọt vào sẽ lặng lẽ tiêu hết hạn mức API.

Cũng chạy OCR khi PDF *có* text nhưng quá ít (`rag.ocr.min-chars-per-page`, mặc định
80). Đây là trường hợp hay bị bỏ sót: file "lai" gồm vài trang đánh máy cộng phần còn
lại là bản scan đính kèm.

Nội dung OCR **phải kiểm tra lại** ở tab *Tài liệu* → *Xem Markdown* trước khi tin dùng.

---

## 7. Giám sát

```bash
curl -s localhost:8080/actuator/health
curl -s -H "X-API-Key: $RAG_ADMIN_API_KEY" localhost:8080/actuator/prometheus
```

Cảnh báo nên đặt:

| Điều kiện | Ý nghĩa |
|---|---|
| `rag_errors_total` tăng | lỗi thật, xem log |
| tỷ lệ từ chối > 40% | tài liệu thiếu, hoặc ngưỡng quá chặt |
| `p95` latency tổng > 25s | LLM chậm hoặc pool nghẽn |
| số thao tác bị từ chối tăng đột biến | có người đang thử quyền |
| backup không sinh file mới trong 24h | cron chết |

Mọi chuỗi cùng một tên metric phải có **cùng bộ khoá tag**, nếu không Prometheus loại
bỏ **im lặng** chuỗi lệch tag. Xem `RagMetricsTest.everyLatencySeriesHasTheSameTagKeys`.

---

## 8. Kiểm thử

```bash
./mvnw test          # toàn bộ, gồm cả test chạm DB thật
```

Các lớp `*IT` dùng Testcontainers (Postgres 17 + pgvector thật) và kiểm tra đúng những
thứ dễ hỏng nhất: 7 migration Flyway, cấu hình text search `vi`, trigger sinh `tsv`,
ACL theo role, lọc category, loại tài liệu hết hiệu lực, và các câu xoá theo thời hạn.

Chúng **tự bỏ qua khi máy không chạy Docker** thay vì làm đỏ build — một bộ test không
chạy được trên máy người khác sẽ bị họ tắt đi, và như thế còn tệ hơn là không có.
CI ([.github/workflows/ci.yml](../.github/workflows/ci.yml)) luôn có Docker nên ở đó
chúng luôn chạy thật.

---

## 9. Sự cố thường gặp

| Hiện tượng | Nguyên nhân |
|---|---|
| Mọi request 401 | thiếu `RAG_ADMIN_API_KEY` |
| Ứng dụng không khởi động, log nói lệch số chiều | đổi model embedding mà chưa tạo lại schema + nạp lại |
| Đăng nhập Entra báo `AADSTS50011` | thiếu `FORWARD_HEADERS=NATIVE` khi đứng sau proxy |
| Câu trả lời hiện một cục thay vì chảy dần | nginx thiếu `proxy_buffering off` |
| Nạp PDF luôn thất bại | bản scan, chưa bật OCR |
| Nạp file nào cũng bị từ chối | `rag.antivirus.enabled=true` nhưng clamd chết (fail-closed) |
| Nhật ký trống | `rag.audit.enabled=false`, hoặc thao tác bị chặn ở tầng rate limit trước khi tới filter |
| Câu trả lời của ADMIN lọt sang USER | khoá cache thiếu phần role — xem `AccessScopeTest` |
