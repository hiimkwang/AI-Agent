# Triển khai lên UAT `uat-chatbot01` (10.21.170.55)

Phương án cho **đúng máy chủ này**, viết sau khi đã đăng nhập kiểm tra thực tế — không
phải hướng dẫn chung. Phần tổng quát xem [OPERATIONS.md](OPERATIONS.md).

## 0. Hiện trạng đã xác minh

| Hạng mục | Thực tế |
|---|---|
| OS | RHEL 9.5, 16 core, 15 GiB RAM (10 GiB trống), 45 GB trống trên `/` |
| Web hiện có | Apache 2.4.62 + PHP 8.3 + Drupal, `Listen 80`, vhost `chatbot-uat.bsc.com.vn` → `/var/www/html/chatbot` |
| TLS | **Không** chạy trên máy này — kết thúc ở lớp biên `103.219.180.171`, forward qua `10.21.170.5` về `:80` |
| Cert | GlobalSign EV, hạn **10/02/2027**, SAN có `chatbot-uat.bsc.com.vn` — còn tốt |
| Java | JDK **21.0.11** đã giải nén sẵn tại `/app/aiagent/lib/jdk-21.0.11` — không cần `dnf install` |
| Postgres | **đã cài** 19/08/2026: podman + `pgvector/pgvector:pg17`, service `rag-postgres` |
| Container | `podman 5.2.2` (không có docker), kéo được `docker.io/pgvector/pgvector:pg17` |
| Maven | **chưa có** → build jar ở máy dev rồi copy lên |
| Module Apache | `mod_proxy`, `proxy_http`, `headers`, `rewrite` **đã nạp sẵn** |
| SELinux / firewalld | Disabled / inactive — không có rào cản cấu hình |
| Chiều ra Internet | HTTPS **thông** (OpenAI, botframework, graph, login.microsoftonline) |
| Cổng còn trống | `8080`, `5432` — host chỉ nghe 80, 22, 10050 |

**Đã kiểm chứng lại 19/08/2026 khi triển khai:**

1. **`dnf` KHÔNG còn hỏng** — `dnf repolist` chạy bình thường. Ghi chú cũ về repo
   `mysql-8.4-lts-community` không còn đúng. Quan trọng hơn: **MySQL đang chạy thật**
   trên máy này (`mysqld.service`, cổng 3306/33060), nên **tuyệt đối không tắt** repo
   đó. Nếu về sau `dnf` lại lỗi vì nó, dùng `--disablerepo='mysql*'` cho từng lệnh.
2. **`/app/chatbot-java`** là bộ khung của một service Java khác (JDK 17), `deploy/`
   rỗng và không có unit systemd — không chạy, không xung đột. Đừng xoá khi chưa hỏi.
3. **Vhost `chatbot-uat2.bsc.com.vn` (phase 2) đã chết** — khai `<VirtualHost *:88>`
   nhưng không có `Listen 88`. Đừng dùng hostname này: nó cũng không nằm trong SAN của cert.
4. Máy có sẵn nhiều agent bảo mật (`kesl` của Kaspersky, `CyM*`, `VES*`, `klnagent`,
   `zabbix`). Chúng chiếm ~5 GiB RAM — đó là lý do `XMX=3g` là trần cố định.

---

## 1. Kiến trúc triển khai

```
Internet
   │  https://chatbot-uat.bsc.com.vn          cert GlobalSign, hạn 02/2027
   ▼
103.219.180.171   lớp biên — kết thúc TLS
   ▼
10.21.170.5       proxy nội bộ
   ▼
10.21.170.55:80   Apache ─┬─ /       → Drupal (GIỮ NGUYÊN)
                          └─ /rag/   → 127.0.0.1:8080/rag/   web + bot Teams
                                            │
                        systemd  aiagent.service  ──┘  jar
                        systemd  rag-postgres.service   podman
```

Ứng dụng được cài bằng **bộ cài chuẩn của Khối CNTT** trong thư mục
[aiagent/](../aiagent) của repo (bản dùng chung, đã chỉnh cho ứng dụng này):
`bin/Linux/install.sh` tự sinh unit file systemd từ `bin/Linux/deploy.env`, nên
không phải sửa tay `/etc/systemd/system/`. Toàn bộ nằm gọn trong `/app/aiagent`:

```
/app/aiagent/
├── deploy/AIAgent-0.2.0.jar        jar build ở máy dev
├── config/aiagent.env              GIÁ TRỊ + bí mật — chmod 600, chỗ DUY NHẤT phải sửa
├── config/application.properties   bản đồ khoá→biến, KHÔNG chứa giá trị
├── config/logback.xml              plumbing ghi log
├── logs/                           log ứng dụng + log cron sao lưu
├── work/                           dữ liệu lúc chạy: tai-lieu/, backup/
└── bin/Linux/{deploy.env,install.sh,uninstall.sh,common.sh}
```

**Không có gì nằm ngoài `/app/aiagent`.** `install.sh` tự tạo `logs/`, `work/` và
tài khoản hệ thống `aiagent` — không có bước `mkdir`/`useradd` thủ công nào. Gỡ cài
đặt là xoá đúng một thư mục.

**Chỉ sửa một file: `config/aiagent.env`.** Nó được **cả hai** unit systemd đọc
(`aiagent.service` và `rag-postgres.service`), nên mật khẩu CSDL chỉ khai một lần.

`config/application.properties` **không chứa giá trị nào** — mọi dòng chỉ chuyển tiếp
một biến môi trường (`key=${BIEN:mặc-định}`), đúng như jar làm. Nó ở đó để liệt kê
những khoá bản triển khai này quan tâm. **Đừng thay `${BIEN}` bằng giá trị cứng**:
làm vậy là giết placeholder và biến tương ứng trong `aiagent.env` mất tác dụng.

`deploy/aiagent.service` trong repo là bản viết tay **đời trước**, giữ lại để tham
khảo. Trên máy này dùng bộ cài — đừng cài cả hai, sẽ có hai unit tranh cổng 8080.

**Nguyên tắc:** CSDL **chỉ bind `127.0.0.1`**, không bao giờ lộ ra mạng. Ứng dụng nhận
đúng một tiền tố `/rag/` qua Apache — cả giao diện web lẫn endpoint bot đều đi đường đó,
không có đường nào khác từ Internet.

Ứng dụng hiện **cố ý nghe mọi interface** để còn vào được `http://10.21.170.55:8080/rag/`
khi Apache có sự cố (vẫn phải có API key hoặc đăng nhập Entra). Khi không cần đường đó
nữa, đổi `SERVER_ADDRESS=127.0.0.1` trong `config/aiagent.env` để đóng hẳn cổng 8080.

### Tiền tố `/rag` — thứ dễ hỏng nhất

Tiền tố do **ứng dụng** sinh ra (`SERVER_CONTEXT_PATH=/rag`), không phải do Apache thêm
vào: Apache chuyển `/rag/` sang `:8080/rag/` **giữ nguyên tiền tố**, không cắt. Hệ quả
là nó có mặt cả khi SSH tunnel nối thẳng cổng 8080, bỏ qua Apache.

| Nơi | Giá trị | Lệch thì bị gì |
|---|---|---|
| `config/aiagent.env` | `SERVER_CONTEXT_PATH=/rag` | Apache proxy tới đường không tồn tại → 404 toàn bộ |
| `/etc/httpd/conf.d/chatbot.conf` | `ProxyPass /rag/ → :8080/rag/` | Như trên |
| `bin/Linux/deploy.env` | `HEALTH_URL=…/rag/actuator/health` | `install.sh` probe hỏng, báo cài thất bại dù app đã lên |
| Entra Redirect URI | `…/rag/login/oauth2/code/entra` | `AADSTS50011` sau khi chọn tài khoản |

Giao diện web tự bám tiền tố qua hàm `url()` trong
[app.js](../src/main/resources/static/app.js), lấy từ chính `src` của `app.js`. Thêm một
`fetch('/api/...')` tuyệt đối mà **không** bọc `url()` thì chạy đúng ở gốc và **404 dưới
tiền tố** — lỗi chỉ hiện trên máy chủ, không hiện khi chạy local.

---

## 2. Các bước thực hiện

### Bước 0 — Kiểm tra `dnf` và JDK

Trên máy này **không cần cài gì bằng `dnf`**: JDK 21 đã được giải nén sẵn vào
`/app/aiagent/lib/jdk-21.0.11`, và `install.sh` tự tìm `<APP_DIR>/lib/jdk*` trước khi
tìm `java` trên PATH.

```bash
/app/aiagent/lib/jdk-21.0.11/bin/java -version    # phải ra 21.x
dnf repolist                                       # chạy xong = dnf lành
```

Nếu `dnf` báo lỗi vì repo `mysql*`, **đừng tắt nó** — MySQL đang chạy thật trên máy.
Dùng `--disablerepo='mysql*'` cho từng lệnh, hoặc sửa `baseurl` trong
`/etc/yum.repos.d/mysql-community.repo` từ `http://` sang `https://`.

### Bước 1 — Build jar ở máy dev rồi copy bộ cài lên

Máy chủ không có Maven và không nên cài — build ở máy dev sạch hơn.

```bash
# trên server — scp không tự tạo thư mục cha
mkdir -p /app
```

```powershell
# máy dev
./mvnw clean package
Copy-Item target\AIAgent-0.2.0.jar aiagent\deploy\
scp -r aiagent root@10.21.170.55:/app/aiagent
scp deploy/backup.sh deploy/restore.sh root@10.21.170.55:/app/aiagent/deploy/
```

```bash
# trên server — scp từ Windows để lại CRLF, script sẽ không chạy được
cd /app/aiagent/bin/Linux && sed -i 's/\r$//' *.sh deploy.env
chmod +x /app/aiagent/bin/Linux/*.sh /app/aiagent/deploy/*.sh
```

`install.sh` tự sửa CRLF cho `deploy.env` và `config/aiagent.env`, nhưng **không tự
sửa được chính nó** — nên dòng `sed` ở trên là bắt buộc.

`backup.sh`/`restore.sh` đã tự dò `docker` rồi tới `podman`, không phải sửa tay nữa.
Đặt `CONTAINER_CLI=podman` nếu muốn ép.

### Bước 2 — Cấu hình (một file duy nhất)

```bash
cd /app/aiagent/config
cp aiagent.env.example aiagent.env
chmod 600 aiagent.env
openssl rand -hex 24          # RAG_ADMIN_API_KEY
openssl rand -hex 24          # RAG_USER_API_KEY
vi aiagent.env
```

Bốn giá trị bắt buộc phải điền:

| Biến | Ghi chú |
|---|---|
| `POSTGRES_PASSWORD` | Container Postgres dùng để tạo role, ứng dụng dùng để kết nối — **một biến, một chỗ** |
| `RAG_ADMIN_API_KEY` | Thiếu ⇒ mọi request 401 |
| `RAG_USER_API_KEY` | |
| `OPENAI_API_KEY` | Dùng cho cả embedding, sinh câu trả lời, rerank và OCR |

Phần còn lại đã điền sẵn đúng cho máy này: `DB_URL`, `SERVER_ADDRESS=0.0.0.0`,
`FORWARD_HEADERS=NATIVE`, `RAG_TRUST_PROXY=true`,
`RAG_ALLOWED_ROOTS=/app/aiagent/work/tai-lieu`,
`RAG_LOG_QUESTIONS=false`.

> **Chốt trước lần khởi động đầu tiên:** `RAG_EMBEDDING_PROVIDER` và
> `RAG_EMBEDDING_DIM`. Số chiều đi thẳng vào DDL của Flyway; đổi sau khi đã nạp tài
> liệu là phải tạo lại schema và nạp lại toàn bộ. `SchemaValidator` chặn khởi động
> nếu cấu hình lệch với CSDL, nên gõ nhầm sẽ lộ ra ngay chứ không âm thầm hỏng.

Không cần `chown`: `install.sh` ở bước 4 tạo tài khoản `aiagent` rồi đặt lại chủ sở
hữu cho cả `/app/aiagent`, và chmod 600 lại file này.

File này systemd đọc theo `KEY=value`, **không phải cú pháp shell**: không `export`,
không `$(lệnh)`, không `${BIEN_KHAC}`, không dấu cách quanh `=`.

Bot Teams và Entra bật sau bằng cách bỏ dấu `#` ngay trong file này — xem
[TEAMS-BOT-SETUP.md](TEAMS-BOT-SETUP.md) và [ENTRA-SETUP.md](ENTRA-SETUP.md).

### Bước 3 — Postgres 17 + pgvector bằng podman, quản lý như service

`pgvector` không có trong repo hiện tại của máy. Dùng image chính thức là cách chắc chắn
nhất để có đúng extension và đúng phiên bản — và **khớp y hệt** image mà
`docker-compose.yml` lẫn bộ test Testcontainers đang dùng, nên `SchemaValidator` hành xử
giống hệt lúc chạy test.

Quadlet đọc `/app/aiagent/config/aiagent.env`, nên bước này phải nằm sau bước 2.

```bash
podman volume create rag_pgdata
```

Tạo `/etc/containers/systemd/rag-postgres.container` — quadlet, podman tự sinh service:

```ini
[Unit]
Description=Postgres 17 + pgvector cho AI-Agent
After=network-online.target
Wants=network-online.target

[Container]
ContainerName=rag-postgres
Image=docker.io/pgvector/pgvector:pg17
# CHI bind localhost - DB khong bao gio duoc lo ra mang
PublishPort=127.0.0.1:5432:5432
Environment=POSTGRES_DB=rag_db
Environment=POSTGRES_USER=rag
Environment=TZ=Asia/Ho_Chi_Minh
# Dung CHUNG file cau hinh voi ung dung: mat khau chi khai mot lan.
EnvironmentFile=/app/aiagent/config/aiagent.env
Volume=rag_pgdata:/var/lib/postgresql/data
# HNSW can maintenance_work_mem lon khi build index
Exec=postgres -c maintenance_work_mem=512MB -c shared_buffers=1GB -c max_connections=100
HealthCmd=pg_isready -U rag -d rag_db
HealthInterval=10s
HealthRetries=20

[Service]
Restart=always
TimeoutStartSec=300

[Install]
WantedBy=multi-user.target
```

```bash
systemctl daemon-reload
systemctl start rag-postgres.service
podman exec rag-postgres psql -U rag -d rag_db -c 'SELECT version();'
```

> **Nếu chính sách nội bộ không cho dùng container:** repo PGDG
> (`download.postgresql.org`) truy cập được từ máy này, cài `postgresql17-server`
> + `pgvector_17` được. Tốn thêm việc nhưng khả thi.

### Bước 4 — Cài service

`deploy.env` là thiết lập **cài đặt** (đường dẫn, tên jar, RAM, user chạy service),
không phải cấu hình vận hành — bình thường không phải sửa. Đã điền sẵn cho máy này.

```bash
cd /app/aiagent/bin/Linux
./install.sh --dry-run          # xem trước unit file, KHÔNG đổi gì trên máy
sudo ./install.sh
```

`install.sh` tự làm: kiểm tra Java đúng 21, tạo user `aiagent`, chmod 600 file bí mật,
sinh `/etc/systemd/system/aiagent.service`, `enable --now`, rồi probe
`http://127.0.0.1:8080/rag/actuator/health` tối đa 120 giây.

Nó **dừng ngay** nếu thiếu `config/aiagent.env` — cố ý, vì `EnvironmentFile` không
có dấu `-`. Bỏ qua thì ứng dụng vẫn khởi động với mật khẩu mặc định `admin` và
`RAG_ADMIN_API_KEY` rỗng ⇒ mọi request 401, rất mất thời gian mới tìm ra.

```bash
journalctl -u aiagent -f
tail -f /app/aiagent/logs/aiagent.log
```

Log khởi động phải có `Schema OK: vector(1536) khop cau hinh`. Nếu báo lệch số chiều
thì **dừng lại**, đừng nạp tài liệu — xem lại `RAG_EMBEDDING_DIM`.

Sau này: đổi `config/aiagent.env` ⇒ `systemctl restart aiagent`. Nếu đổi
`POSTGRES_PASSWORD` thì phải `systemctl restart rag-postgres` trước — nhưng lưu ý
Postgres chỉ dùng biến đó lúc **khởi tạo volume lần đầu**; đổi mật khẩu của một CSDL
đã có dữ liệu phải làm bằng `ALTER ROLE`.
Đổi `deploy.env` hoặc tên jar ⇒ chạy lại `install.sh` (giá trị đó nằm trong unit file).

### Bước 5 — Mở đường cho bot qua Apache

Backup trước, rồi thêm vào **trong** khối `<VirtualHost *:80>` của
`/etc/httpd/conf.d/chatbot.conf`:

```apache
    # ---- AI-Agent: CHI mo dung endpoint bot Teams ----
    ProxyPreserveHost On
    ProxyTimeout 120
    # Lop bien da ket thuc TLS nhung forward bang HTTP. Khong co dong nay thi Spring
    # sinh redirect_uri la http:// va Microsoft tra AADSTS50011.
    RequestHeader set X-Forwarded-Proto "https"
    RedirectMatch ^/rag$ /rag/
    <Location /rag/>
        RequestHeader setifempty X-Forwarded-Proto https
        ProxyPass        http://127.0.0.1:8080/rag/ flushpackets=on connectiontimeout=5 timeout=300
        ProxyPassReverse http://127.0.0.1:8080/rag/
    </Location>
```

```bash
cp /etc/httpd/conf.d/chatbot.conf /etc/httpd/conf.d/chatbot.conf.bak.$(date +%F)
# ... sửa file ...
httpd -t && systemctl reload httpd
```

Messaging endpoint khai với Azure Bot: `https://chatbot-uat.bsc.com.vn/rag/api/messages`

### Bước 6 — Sao lưu tự động

```bash
crontab -u root -e
# 15 2 * * * DB_USER=rag /app/aiagent/deploy/backup.sh >> /app/aiagent/logs/backup.log 2>&1
```

Chạy `backup.sh` một lần **ngay hôm nay** và thử `restore.sh` sang một DB khác — bản sao
lưu chưa khôi phục thử thì chưa phải bản sao lưu.

### Bước 7 — Nạp tài liệu thật và đo

Chép tài liệu vào `/app/aiagent/work/tai-lieu` trước (`install.sh` đã tạo `work/`;
thư mục con tạo bằng `mkdir -p`).

```bash
curl -s -X POST http://127.0.0.1:8080/rag/api/v1/rag/admin/ingest-folder \
  -H "X-API-Key: $RAG_ADMIN_API_KEY" -H 'Content-Type: application/json' \
  -d '{"path":"/app/aiagent/work/tai-lieu","category":"chung"}'
```

Rồi tạo bộ câu hỏi chuẩn và đo baseline:

```bash
# sinh bộ câu hỏi từ chính kho tài liệu (nhãn có sẵn: nguồn đúng là file chứa đoạn đó)
curl -s -X POST http://127.0.0.1:8080/rag/api/v1/rag/eval/cases/generate \
  -H "X-API-Key: $RAG_ADMIN_API_KEY" \
  -H 'Content-Type: application/json' -d '{}'

# đo recall@k + MRR — rẻ, deterministic, không tốn LLM giám khảo
curl -s -X POST http://127.0.0.1:8080/rag/api/v1/rag/eval/retrieval \
  -H "X-API-Key: $RAG_ADMIN_API_KEY" \
  -H 'Content-Type: application/json' -d '{}'
```

---

## 3. Nghiệm thu

| # | Kiểm tra | Đạt khi |
|---|---|---|
| 1 | `systemctl is-active aiagent rag-postgres` | cả hai `active` |
| 2 | `curl localhost:8080/rag/actuator/health` | `{"status":"UP"}` |
| 3 | Log khởi động | `Schema OK: vector(1536) khop cau hinh` |
| 4 | `ss -lntp \| grep 5432` | **chỉ** `127.0.0.1:5432` — CSDL không được lộ ra mạng |
| 5 | `curl https://chatbot-uat.bsc.com.vn/` từ ngoài | vẫn ra Drupal — **không hỏng gì** |
| 6 | Nạp 30–50 tài liệu thật | tỷ lệ `failed` thấp; file scan ⇒ cần bật OCR |
| 7 | Hỏi câu có trong tài liệu | trả lời kèm nguồn đúng |
| 8 | Hỏi câu không có trong tài liệu | **từ chối**, không bịa |
| 9 | Tab Nhật ký | có ghi các thao tác quản trị vừa làm |
| 10 | `systemctl restart aiagent` | tự lên lại, dữ liệu còn nguyên |

---

## 4. Rủi ro và cách chặn

| Rủi ro | Chặn bằng |
|---|---|
| Làm hỏng Drupal đang chạy | Chỉ **thêm** `ProxyPass`, không sửa dòng có sẵn; backup file conf; `httpd -t` trước khi reload |
| Đổi model embedding sau khi đã nạp | Chốt `RAG_EMBEDDING_DIM` ở bước 2, trước lần khởi động đầu tiên. `SchemaValidator` chặn khởi động khi cấu hình lệch với CSDL, nên gõ nhầm lộ ra ngay chứ không âm thầm hỏng kết quả tìm kiếm |
| DB lộ ra mạng | `PublishPort=127.0.0.1:5432:5432` |
| App lộ ra mạng | Giai đoạn 1 chấp nhận có chủ đích (web nội bộ), vẫn có API key chặn. Giai đoạn 2 bật `server.address=127.0.0.1` |
| Secret lọt vào git hoặc log | `config/aiagent.env` chmod 600 và đã có trong `.gitignore`; `install.sh` tự chmod lại mỗi lần cài; `RAG_LOG_QUESTIONS=false` |
| Container Postgres nhìn thấy `OPENAI_API_KEY` | Hệ quả của việc dùng chung một file env. Container chỉ bind loopback và chạy image chính thức nên rủi ro thấp; muốn tách thì cho quadlet trỏ `EnvironmentFile` sang một file riêng chỉ chứa `POSTGRES_PASSWORD` |
| Hết dung lượng do vector/log | 45 GB trống; theo dõi `/var/lib/containers` và `/app/aiagent` |
| PDF scan không nạp được | Bật `RAG_OCR_ENABLED=true` sau khi đo tỷ lệ file scan |

## 5. Nên làm nhân dịp này (không bắt buộc)

- `/etc/httpd/conf.d/` đang `777`, `chatbot.conf` cũng `777` → đưa về `755`/`644`, chủ `root`
- `LogFormat` đang ghi `%h` = IP proxy (`10.21.170.5`), không phải IP người dùng thật →
  đổi sang `%{X-Forwarded-For}i`. Ảnh hưởng trực tiếp tới nhật ký kiểm toán của ứng dụng
- Xoá `ssl.conf_bk`, `chatbot.conf_bk` và cert đã hết hạn `SSL2024V10.cer` (hạn 02/03/2025)
  cho khỏi gây nhầm lẫn về sau
- Đổi mật khẩu `root`, chuyển sang SSH key + tài khoản `sudo` riêng
