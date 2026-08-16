# Nâng cấp model embedding

Đổi model embedding là thay đổi **đắt giá nhất** trong hệ thống: phải tạo lại schema và
nạp lại **toàn bộ** tài liệu, sai thì làm lại lần nữa. Tài liệu này là quy trình để quyết
định dựa trên số liệu chứ không phải cảm giác, và để bước đổi thật không làm gián đoạn
người dùng.

> **Bất biến không được phá:** vector của **câu hỏi** và của **tài liệu** phải cùng một
> model. `SchemaValidator` chặn lúc khởi động nếu cấu hình lệch DB — đừng lách nó.

---

## 0. Bộ câu hỏi chuẩn — không phải ngồi gắn nhãn tay

Bộ 100 câu hỏi thật có gắn nhãn nguồn chỉ có sau vài tháng vận hành. Bắt phải có nó
*trước* khi đo được bất cứ thứ gì là bài toán con gà — quả trứng. Có hai đường lấy bộ đo
mà không ai phải ngồi gắn nhãn:

### a) Sinh từ chính kho tài liệu — dùng được ngay ngày đầu

Với mỗi đoạn tài liệu, model nội bộ viết một câu hỏi mà đoạn đó trả lời được. **Nguồn
đúng chính là file chứa đoạn đó**, nên nhãn có sẵn.

```bash
curl -X POST -H "X-API-Key: $K" -H 'Content-Type: application/json' \
     -d '{"suite":"chuan","perDocument":5}' \
     https://<host>/api/v1/rag/eval/cases/generate

curl -H "X-API-Key: $K" https://<host>/api/v1/rag/eval/cases/build-status
```

Lấy mẫu **rải đều theo tài liệu** (`perDocument`), không lấy ngẫu nhiên toàn cục — nếu
không, một quy chế 500 trang sẽ chiếm gần hết bộ đo và bộ đo sẽ đo "tìm trong một tài
liệu" chứ không phải "tìm đúng tài liệu". 40 tài liệu × 5 = 200 case trong vài phút,
chi phí là 200 lần gọi model nội bộ rẻ.

> **Giới hạn phải biết:** câu hỏi được sinh *từ* đoạn tài liệu nên dùng chung từ vựng với
> đoạn đó ⇒ dễ hơn câu hỏi thật, và **con số recall tuyệt đối sẽ cao hơn thực tế**. Nhưng
> độ lệch này tác động như nhau lên mọi cấu hình được đem ra so, nên phép **so sánh** vẫn
> có giá trị. Dùng nó để trả lời *"cấu hình nào tốt hơn"*, đừng dùng để trả lời *"hệ thống
> tốt đến đâu"*.

### b) Thu hoạch từ log thật — bộ đo tự lớn lên

Sau khi chạy, `rag_messages` đã có câu hỏi thật kèm trích dẫn. Lấy luôn làm case:

```bash
# Câu hỏi thật + nguồn hệ thống đã trích (bỏ các câu bị 👎)
curl -X POST -H "X-API-Key: $K" -H 'Content-Type: application/json' \
     -d '{"suite":"thuc-te","sinceDays":90}' \
     https://<host>/api/v1/rag/eval/cases/harvest

# Các câu bị 👎 -> bộ riêng, CHƯA có nguồn đúng, là danh sách việc cần người xem lại
curl -X POST -H "X-API-Key: $K" -H 'Content-Type: application/json' \
     -d '{"negative":true}' https://<host>/api/v1/rag/eval/cases/harvest
```

Chạy lại bao nhiêu lần cũng được — trùng câu hỏi được khử tự động (bỏ dấu, bỏ hoa thường,
bỏ dấu câu).

> **Nhãn ở đây là "hệ thống ĐÃ tìm ra cái gì", không phải "câu trả lời đúng là gì".** Nên
> bộ này đo được **hồi quy** — một thay đổi có làm hỏng những gì đang chạy tốt không —
> nhưng không nói được hệ thống vốn đã sai từ đầu. Đó là lý do vẫn cần bộ `can-xem-lai`
> cho người thật rà.

### Nên dùng cái nào

Dùng **cả hai**: (a) cho quyết định *hôm nay*, (b) để bộ đo dần phản ánh thực tế. Câu hỏi
gắn nhãn tay vẫn là tốt nhất, nhưng là thứ **bổ sung dần**, không phải điều kiện tiên quyết.

Đặt lịch chạy (b) hằng tuần thì sau một quý bạn có bộ đo thật mà không ai phải làm gì.

### Lấy baseline

```bash
curl -X POST -H "X-API-Key: $K" -H 'Content-Type: application/json' \
     -d '{"suite":"chuan"}' https://<host>/api/v1/rag/eval/retrieval
```

Ghi lại `recall@k` và `mrr` — đây là mốc để so mọi thay đổi về sau.

---

## 1. Thử model ứng viên — không đụng vào index đang chạy

Cơ chế: nhúng lại **chính các chunk đang có** bằng model ứng viên vào một bảng phụ chỉ
chứa `chunk_id + embedding`, rồi đo cả hai bên trên cùng bộ câu hỏi. Điều kiện lọc ACL,
cách bổ chunk, bộ câu hỏi đều y hệt — phép so sánh chỉ khác **đúng một biến**: model.

```powershell
$env:RAG_EMBEDDING_TRIAL        = 'true'
$env:RAG_EMBEDDING_TRIAL_MODEL  = 'text-embedding-3-large'
$env:RAG_EMBEDDING_TRIAL_DIM    = '3072'
```

Khởi động lại, rồi:

```bash
# Nhúng lại toàn bộ chunk bằng model ứng viên (chạy nền)
curl -X POST -H "X-API-Key: $K" "https://<host>/api/v1/rag/admin/embedding-trial/build?rebuild=true"

# Theo dõi tiến độ
curl -H "X-API-Key: $K" https://<host>/api/v1/rag/admin/embedding-trial

# So sánh
curl -X POST -H "X-API-Key: $K" -H 'Content-Type: application/json' \
     -d '{"suite":"chuan"}' https://<host>/api/v1/rag/admin/embedding-trial/compare
```

Kết quả trả về recall@1/3/5/10 và MRR của **cả hai bên**, kèm một câu kết luận. Nếu bộ
câu hỏi dưới 30 case, kết luận sẽ kèm cảnh báo là quá nhỏ để quyết định.

**Chi phí bước này:** một lần nhúng toàn bộ chunk bằng model ứng viên. Với
`text-embedding-3-large` và ~50k chunk thì vẫn rẻ hơn nhiều so với việc nạp lại sai rồi
phải nạp lại lần nữa.

Phép so sánh này cố ý chỉ dùng **nhánh vector thuần**, không hybrid: nhánh full-text giống
hệt nhau ở cả hai bên nên chỉ làm loãng khác biệt cần đo.

### ⚠️ Giới hạn cứng: HNSW không quá 2000 chiều

pgvector **không tạo được index HNSW cho cột kiểu `vector` quá 2000 chiều**:

```
ERROR: column cannot have more than 2000 dimensions for hnsw index
```

Nghĩa là `text-embedding-3-large` ở **3072 chiều mặc định không dùng trực tiếp làm index
chính được** — `V1__rag_core_schema.sql` tạo index HNSW nên migration sẽ **hỏng ngay lúc
khởi động**. Đây là lỗi gặp thật khi chạy thử, không phải lo xa.

Ba cách xử lý, theo thứ tự nên chọn:

1. **Giảm số chiều của `3-large` xuống ≤ 2000** — khuyến nghị. OpenAI hỗ trợ cắt ngắn
   vector qua tham số `dimensions` (Matryoshka), chất lượng giảm rất ít. Đặt
   `RAG_EMBEDDING_DIM=1536` (hoặc 1024) và giữ nguyên mọi thứ khác. `EmbeddingService`
   đã truyền `dimensions` xuống OpenAI nên chỉ là đổi cấu hình.
2. **Đổi cột sang `halfvec`** — pgvector hỗ trợ HNSW tới 4000 chiều với `halfvec`.
   Giữ trọn 3072 chiều nhưng phải sửa migration và toán tử index
   (`halfvec_cosine_ops`) — chưa làm.
3. **Bỏ index, quét toàn bảng** — chỉ hợp lý với kho nhỏ. Bảng *thử nghiệm* đang làm
   đúng thế này (và quét toàn bảng còn cho phép đo **chính xác tuyệt đối**), nhưng
   index chính thì không.

### Ứng viên đáng thử

| Model | Chiều | Ghi chú |
|---|---|---|
| `text-embedding-3-large` **ở 1536** | 1536 | **Khuyến nghị.** Vẫn tốt hơn `3-small` mà giữ được HNSW và không đổi số chiều schema ⇒ **không cần tạo lại schema**, chỉ nạp lại tài liệu |
| `text-embedding-3-large` ở 3072 | 3072 | Chất lượng cao nhất nhưng cần `halfvec` (xem trên). Đo thử được ngay, dùng thật thì phải sửa migration |
| `bge-m3` qua Ollama | 1024 | Multilingual, mạnh tiếng Việt, **self-host** nên dữ liệu không rời hạ tầng. Cần GPU để nhanh |
| `text-embedding-3-small` | 1536 | Đang dùng — mốc so sánh |

> Chọn `3-large` ở 1536 chiều là đường ít rủi ro nhất: số chiều không đổi nên schema giữ
> nguyên, chỉ cần nạp lại tài liệu và xoá cache.

---

## 2. Đổi thật

Chỉ làm khi bước 1 cho thấy **hơn rõ ràng** (MRR chênh > 0.02 trên bộ ≥ 50 câu).

Đổi số chiều nghĩa là đổi luôn DDL: `rag.embedding.dimensions` được truyền vào Flyway qua
placeholder `${embeddingDim}`, nên cột `vector(n)` sinh theo cấu hình.

### Cách an toàn: dựng song song rồi chuyển

Tránh cửa sổ chết. Cần một DB (hoặc schema) thứ hai:

1. Dựng instance thứ hai trỏ vào DB mới, với cấu hình model mới.
2. Nạp lại toàn bộ tài liệu vào đó (`/admin/ingest-folder` hoặc đồng bộ nguồn).
3. Chạy `/eval/retrieval` trên instance mới, đối chiếu với baseline ở bước 0.
4. Chuyển reverse proxy sang instance mới.
5. Giữ instance cũ vài ngày để còn quay lại được.

### Cách đơn giản: đổi tại chỗ, chấp nhận gián đoạn

```powershell
# 1. Chặn ingest, thông báo bảo trì
# 2. Sao lưu
docker exec rag-postgres pg_dump -U admin rag_db > backup-truoc-doi-embedding.sql

# 3. Xoá dữ liệu vector và lịch sử Flyway của schema cũ
#    (rag_documents.markdown được giữ nên KHÔNG phải convert lại file gốc)

# 4. Đổi cấu hình
$env:RAG_EMBEDDING_OPENAI_MODEL = 'text-embedding-3-large'
$env:RAG_EMBEDDING_DIM          = '3072'

# 5. Khởi động lại -> Flyway tạo lại schema theo số chiều mới
# 6. Nạp lại toàn bộ tài liệu
# 7. Chạy lại /eval/retrieval, so với baseline bước 0
```

> **Cache câu trả lời phải xoá.** `rag_answer_cache` giữ vector câu hỏi theo model **cũ**;
> để lại thì cache semantic sẽ so vector khác hệ và trả về câu trả lời sai ngữ cảnh.
> `DELETE /api/v1/rag/admin/cache` hoặc `TRUNCATE rag_answer_cache`.

### Sau khi đổi xong

```bash
curl -X DELETE -H "X-API-Key: $K" https://<host>/api/v1/rag/admin/embedding-trial
```

Bảng thử nghiệm chiếm chỗ không nhỏ (3072 chiều × số chunk), xoá đi khi đã quyết định.

---

## 3. Rerank — quyết định hiện tại

Đang dùng **LLM rerank** (`rag.rerank.provider=LLM`) và giữ nguyên theo yêu cầu chi phí.

Cần biết để cân nhắc sau này:

- LLM rerank tốn **một lần gọi LLM cho mỗi câu hỏi**, đọc tới `rag.retrieval.candidates`
  (mặc định 36) ứng viên. Đây thường là khoản tốn thứ hai sau bước sinh câu trả lời.
- Muốn giảm chi phí mà chưa đổi provider: hạ `retrieval.candidates` (36 → 24) và dùng
  model nội bộ rẻ cho `rag.internal.model`. Đo lại bằng
  `/eval/retrieval` với `includeRerank=true` để biết mình đã đánh đổi bao nhiêu.
- Khi sẵn sàng chi trả, đổi sang Cohere là **một dòng cấu hình**:
  `rag.rerank.provider=COHERE` + `COHERE_API_KEY`. `CohereReranker` đã có sẵn.
  Tự host `bge-reranker-v2-m3` qua TEI là phương án miễn phí, nhưng cần GPU và cần viết
  thêm một `Reranker`.

Dù đổi hay không, **giữ nguyên phân biệt `reliable` vs `degraded`** của `RerankResult`:
"không có gì liên quan" và "bộ rerank bị lỗi" phải dẫn tới hai hành vi khác nhau, nếu
không hệ thống sẽ im lặng từ chối trả lời khi reranker chết.

---

## 4. Đo tác động sau khi đổi

`/eval/retrieval` đo **truy xuất**. Sau khi đổi model xong, chạy thêm một lần
`/eval/run` (có giám khảo LLM) để xác nhận chất lượng **câu trả lời** cũng không tụt —
truy xuất tốt hơn mà câu trả lời tệ đi là chuyện hiếm nhưng không phải không có, thường do
ngưỡng từ chối (`min-vector-score`) đặt theo thang điểm của model cũ.

Model mới có phân bố điểm cosine khác ⇒ **xem lại `retrieval.minVectorScore`** sau khi đổi.
Đây là chỗ dễ quên nhất: ngưỡng cũ có thể làm bot từ chối trả lời hàng loạt, hoặc ngược lại
không còn từ chối gì cả.
