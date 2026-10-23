# Donghua — App xem hoạt hình 3D Trung Quốc (donghua)

App Android xem donghua lấy nguồn từ **hhkungfu.ee**: hình sắc nét
(web gắn nhãn 4K, nguồn phát 1080P V1/V2), chọn được bản
**Thuyết Minh / Vietsub**, có **lịch chiếu theo ngày trong tuần**.

## Chức năng

- **Trang chủ**: Mới cập nhật / Xem nhiều / Hoàn thành + ô tìm kiếm
- **Tab Mới cập nhật / Xem nhiều / Hoàn thành**: duyệt theo đúng các
  mục của web, bấm "Tải thêm phim" để xem tiếp (phân trang /page/N)
- **Tab Lịch chiếu**: chọn ngày trong tuần, có mục Phim Chiếu Sớm
- **Chi tiết phim**: mô tả, trạng thái, danh sách tập theo từng bản
  chiếu (Vietsub / Thuyết Minh); bấm tập là phát
- **Trình phát**: tự dò luồng phát trực tiếp từ trang embed, chặn cửa
  sổ quảng cáo bật lên + khóa điều hướng trang lạ; nút "Bản chiếu"
  chuyển qua lại giữa các bản (Thuyết Minh/Vietsub × 1080P V1/V2);
  thanh tên phim tự ẩn, chạm màn hình để hiện lại
- **Yêu thích + Lịch sử xem**, thông báo khi phim yêu thích có tập mới
- **Chống đổi tên miền**: app đọc `config.json` ở thư mục gốc repo này —
  web đổi tên miền chỉ cần sửa file đó, không phải cài lại app

## Nền tảng kỹ thuật

Kotlin + WebView (phát qua trang embed + hls.js nội bộ), dữ liệu lấy
bằng Jsoup từ HTML của web (WordPress theme Halim) và endpoint
`player.php` của web để lấy trang phát embed.

Build APK tự động bằng GitHub Actions (file workflow do chủ repo dán
qua giao diện web; bản sao nội dung nằm ở `WORKFLOW-PASTE.txt`).
