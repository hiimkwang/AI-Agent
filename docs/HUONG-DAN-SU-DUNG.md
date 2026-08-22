---
title: Hướng dẫn sử dụng BSC Assistant
category: huong-dan
doc_number: HD-BSCA-01
version: "1.0"
---

# Hướng dẫn sử dụng BSC Assistant

BSC Assistant là trợ lý hỏi–đáp trên tài liệu nội bộ của BIDV Securities. Bạn hỏi bằng
tiếng Việt tự nhiên, trợ lý tìm trong kho tài liệu đã được nạp và trả lời **kèm trích dẫn
nguồn** để bạn tự kiểm chứng.

Tài liệu này được nạp vào chính kho tài liệu của trợ lý, nên bạn có thể hỏi thẳng trợ lý
những câu như *"làm sao để xem nguồn của câu trả lời?"* hoặc *"vì sao trợ lý trả lời là
không tìm thấy?"* thay vì mở file này ra đọc.

## BSC Assistant là gì và không phải là gì

BSC Assistant **chỉ trả lời dựa trên tài liệu nội bộ đã được nạp vào hệ thống**. Đây là
điểm khác biệt quan trọng nhất so với ChatGPT hay Gemini:

- Trợ lý **không** trả lời bằng kiến thức chung của mô hình ngôn ngữ.
- Trợ lý **không** suy đoán. Không có căn cứ trong tài liệu thì trợ lý nói thẳng là không
  tìm thấy, chứ không bịa ra một câu trả lời nghe hợp lý.
- Trợ lý **không** thấy tài liệu mà bạn không có quyền đọc. Hai người hỏi cùng một câu có
  thể nhận hai câu trả lời khác nhau, vì phạm vi tài liệu của mỗi người khác nhau.

Vì vậy, câu trả lời "tôi không tìm thấy nội dung này trong tài liệu" là một câu trả lời
**đúng**, không phải lỗi. Nó có nghĩa là kho tài liệu chưa có, hoặc bạn chưa được cấp quyền
đọc phần tài liệu đó.

## Hai cách dùng: web và Microsoft Teams

### Dùng trên web

Mở địa chỉ trợ lý trên trình duyệt, đăng nhập bằng **tài khoản công ty** (cùng tài khoản
bạn dùng cho email và Teams). Không cần nhớ thêm mật khẩu nào.

Màn hình chính chia làm hai phần:

- **Cột trái** — lịch sử các cuộc trò chuyện của riêng bạn. Người khác không xem được lịch
  sử của bạn.
- **Vùng giữa** — khung chat và ô nhập câu hỏi.

### Dùng trong Microsoft Teams

Trợ lý cũng hoạt động như một ứng dụng trong Teams:

- **Chat riêng với trợ lý** — giống hệt bản web, nhưng dùng ngay trong Teams.
- **Trong kênh của Team** — gõ `@BSC Assistant` kèm câu hỏi. Lưu ý: câu trả lời trong kênh
  **mọi thành viên của kênh đều đọc được**, nên trợ lý cố ý chỉ tra cứu những nhóm tài liệu
  đã được duyệt cho phép trả lời công khai. Câu hỏi về nội dung nhạy cảm nên hỏi ở chat
  riêng.

Gõ `help` hoặc `trợ giúp` để trợ lý gửi lại lời chào và hướng dẫn ngắn.

## Cách đặt câu hỏi để nhận câu trả lời tốt

Chất lượng câu trả lời phụ thuộc rất nhiều vào cách hỏi. Vài nguyên tắc thực tế:

**Nêu rõ đối tượng và bối cảnh.** Trợ lý không biết bạn thuộc phòng nào hay đang xử lý việc
gì, trừ khi bạn nói ra.

- Chưa tốt: *"Nghỉ phép mấy ngày?"*
- Tốt hơn: *"Nhân viên chính thức được nghỉ phép năm bao nhiêu ngày?"*

**Hỏi một ý mỗi lần.** Câu hỏi gộp nhiều ý khiến phần tìm kiếm bị loãng, và câu trả lời
thường chỉ trúng một nửa.

- Chưa tốt: *"Quy trình nghỉ phép, nghỉ ốm và công tác phí thế nào?"*
- Tốt hơn: hỏi ba câu riêng.

**Dùng đúng từ trong văn bản.** Nếu bạn nhớ số hiệu văn bản, tên quy trình hay tên biểu mẫu,
hãy đưa vào câu hỏi — đó là tín hiệu mạnh nhất để tìm đúng đoạn.

- Ví dụ tốt: *"Điều 12 của Quy chế chi tiêu nội bộ quy định gì về công tác phí?"*

**Không cần gõ dấu.** Hệ thống tìm được cả khi bạn gõ không dấu (*"quy che chi tieu"* vẫn
ra *"Quy chế chi tiêu"*). Nhưng gõ có dấu vẫn cho kết quả tốt hơn.

**Hỏi tiếp trong cùng cuộc trò chuyện.** Trợ lý nhớ ngữ cảnh của các câu trước trong cùng
một cuộc trò chuyện, nên bạn hỏi tiếp *"còn với nhân viên thử việc thì sao?"* là được, không
cần lặp lại toàn bộ câu hỏi.

## Đọc và kiểm chứng câu trả lời

Dưới mỗi câu trả lời có mục **Nguồn tham khảo**. Mỗi nguồn là một thẻ, bấm vào để mở ra:

- **Tên file** tài liệu gốc.
- **Đường dẫn tiêu đề** — vị trí chính xác trong tài liệu (ví dụ `Chương II › Điều 12 ›
  Khoản 3`).
- **Đoạn trích** đúng phần mà trợ lý đã dựa vào.
- **Điểm liên quan** — mức độ khớp mà hệ thống chấm cho đoạn đó.

**Hãy đọc phần trích dẫn trước khi dùng câu trả lời cho việc quan trọng.** Trợ lý tổng hợp
lại bằng lời của mình, còn phần trích dẫn mới là nguyên văn tài liệu.

Trong lúc trợ lý đang xử lý, bạn thấy các bước đang chạy (tiếp nhận câu hỏi, phân tích, tìm
trong tài liệu, chấm điểm liên quan, tổng hợp câu trả lời). Khi câu trả lời bắt đầu hiện ra,
phần này thu gọn lại thành một dòng — bấm vào để xem lại các bước.

## Các nút và tuỳ chọn trên màn hình

| Nút / ô | Công dụng |
|---|---|
| **Cuộc trò chuyện mới** | Bắt đầu chủ đề mới. Nên dùng khi chuyển sang việc khác, để ngữ cảnh cũ không ảnh hưởng câu trả lời mới. |
| **Tìm trong lịch sử** | Lọc nhanh các cuộc trò chuyện cũ theo tiêu đề. |
| **Nhóm** | Giới hạn phạm vi tìm kiếm trong một nhóm tài liệu, ví dụ chỉ tìm trong nhóm Nhân sự. Để "tất cả tài liệu" nếu không chắc. |
| **Nút ↑ (Gửi)** | Gửi câu hỏi. Phím tắt: `Enter`. Xuống dòng: `Shift + Enter`. |
| **Nút ■ (Dừng)** | Hiện ra khi trợ lý đang trả lời. Dừng giữa chừng, phần chữ đã hiện vẫn được giữ lại. Phím tắt: `Esc`. |
| **⧉ Sao chép** | Chép câu trả lời ra clipboard. |
| **👍 / 👎** | Đánh giá câu trả lời. Xem mục dưới. |
| **Biểu tượng mặt trời / mặt trăng** | Đổi giao diện sáng ↔ tối. |

Nếu bạn là quản trị viên, thanh công cụ có thêm ô chọn **Model** và **Dùng cache**. Người
dùng thường không thấy hai ô này, và cũng không cần đến chúng.

## Đánh giá câu trả lời bằng 👍 và 👎

Đây là cách nhanh nhất để giúp trợ lý tốt lên, và nó thực sự được dùng:

- Bấm **👎** khi câu trả lời sai, thiếu, hoặc trích dẫn nhầm văn bản. Hệ thống mở một ô để
  bạn ghi một dòng mô tả sai ở đâu — không bắt buộc, nhưng một dòng của bạn tiết kiệm cho
  người quản trị rất nhiều thời gian dò tìm.
- Bấm **👍** khi câu trả lời đúng và hữu ích.

Toàn bộ phản hồi 👎 hiện trong màn quản trị dưới dạng danh sách việc cần xem lại. Câu hỏi bị
trợ lý từ chối nhiều lần được tổng hợp thành **danh sách tài liệu cần nạp bổ sung**.

## Khi trợ lý trả lời "không tìm thấy"

Có bốn nguyên nhân, xếp theo thứ tự hay gặp:

1. **Kho tài liệu chưa có nội dung đó.** Cách xử lý: bấm 👎 để việc này vào danh sách cần
   nạp, rồi liên hệ bộ phận sở hữu tài liệu.
2. **Bạn chưa được cấp quyền đọc nhóm tài liệu đó.** Cách xử lý: đề nghị quản trị hệ thống
   hoặc người phụ trách nhóm tài liệu cấp quyền cho nhóm Entra của bạn.
3. **Câu hỏi dùng từ khác hẳn với từ trong văn bản.** Cách xử lý: thử hỏi lại bằng đúng
   thuật ngữ trong văn bản, hoặc thêm số hiệu văn bản.
4. **Bạn đang giới hạn ở sai nhóm tài liệu.** Cách xử lý: đổi ô **Nhóm** về "tất cả tài liệu".

Nếu câu hỏi của bạn còn chung chung, trợ lý sẽ **hỏi lại cho rõ** thay vì đoán bừa, và gợi ý
vài cách hỏi cụ thể hơn. Bấm thẳng vào gợi ý là hỏi lại luôn.

## Quyền riêng tư và phạm vi dữ liệu

- Lịch sử trò chuyện của bạn **chỉ mình bạn xem được**. Bạn xoá được từng cuộc hoặc xoá toàn
  bộ bằng nút **Xoá tất cả**.
- Trợ lý **chỉ đọc được những nhóm tài liệu mà nhóm Entra của bạn được cấp quyền**. Không có
  cách nào hỏi vòng để lấy tài liệu ngoài phạm vi đó.
- Trong kênh Teams, quyền bị **thu hẹp thêm một lần nữa**: kể cả quản trị viên hỏi trong kênh
  cũng chỉ tra cứu được những nhóm tài liệu đã duyệt cho trả lời công khai. Đây là chủ ý, để
  một câu hỏi trong kênh không vô tình phơi tài liệu hạn chế cho cả kênh.
- Mọi thao tác làm thay đổi dữ liệu hoặc cấu hình đều được ghi nhật ký, kể cả thao tác bị từ
  chối.
- Hội thoại cũ được tự động dọn theo chính sách vòng đời dữ liệu của hệ thống.

## Câu hỏi thường gặp

**Trợ lý có trả lời sai không?**
Có thể. Trợ lý tổng hợp lại nội dung tài liệu bằng lời của mình, và bước tổng hợp đó có thể
diễn đạt lệch. Đó chính là lý do mọi câu trả lời đều kèm trích dẫn — với việc quan trọng,
hãy đọc phần trích dẫn nguyên văn.

**Vì sao cùng một câu hỏi mà tôi và đồng nghiệp nhận câu trả lời khác nhau?**
Vì phạm vi tài liệu hai người đọc được khác nhau, do khác nhóm Entra.

**Vì sao câu trả lời lần này ra ngay lập tức?**
Câu trả lời được lưu tạm (cache) cho những câu hỏi giống hoặc rất gần nhau. Cache có tính cả
phạm vi quyền của người hỏi, nên không có chuyện nhận nhầm câu trả lời của người khác quyền.

**Tài liệu vừa được nạp, bao lâu thì trợ lý biết?**
Ngay khi job nạp chạy xong. Không cần khởi động lại gì.

**Tôi muốn thêm tài liệu của phòng mình vào trợ lý?**
Đề nghị quản trị hệ thống cấp cho bạn một **tiền tố nhóm tài liệu**. Sau đó bạn vào mục
**Nhóm của tôi** để tự tạo nhóm, nạp tài liệu, và chọn nhóm Entra nào được đọc — không cần
qua quản trị cho từng file.

**Trợ lý đọc được file gì?**
PDF, Word, Excel, PowerPoint, HTML và Markdown. PDF dạng ảnh scan cần bật thêm OCR, nếu chưa
bật thì hệ thống báo lỗi rõ ràng chứ không âm thầm bỏ qua.

**Tôi hỏi bằng tiếng Anh được không?**
Được, nhưng tài liệu nội bộ hầu hết bằng tiếng Việt nên hỏi tiếng Việt cho kết quả tốt hơn
hẳn.

## Cần trợ giúp

- Lỗi giao diện, không đăng nhập được, trợ lý không phản hồi: liên hệ **Khối Công nghệ thông
  tin**.
- Nội dung tài liệu sai hoặc thiếu: liên hệ bộ phận sở hữu tài liệu đó, và nhớ bấm 👎 để việc
  được ghi nhận vào hệ thống.
- Xin quyền đọc thêm nhóm tài liệu: liên hệ người phụ trách nhóm tài liệu đó hoặc quản trị hệ
  thống.
