# Chia tiền Đà Lạt

App web chia chi tiêu cho nhóm 5 người đi Đà Lạt 5 ngày. Mỗi người tự nhập chi
tiêu trong chuyến, tất cả đồng bộ real-time, cuối chuyến app tính ra danh sách
chuyển khoản tối giản để cân bằng cả nhóm.

Kotlin Multiplatform (Compose Multiplatform for Web, target `wasmJs`) + Supabase.
Không có backend riêng — app gọi thẳng Supabase, bảo vệ bằng RLS.

## Ba loại chi tiêu

| Loại | Ý nghĩa | Cách chia |
|---|---|---|
| Nhóm — chia đều | Khách sạn, thuê xe, lẩu nướng | Chia đều cho những ai được chọn, chính xác tới từng đồng |
| Cá nhân — tự trả | Tự mua tự trả | Không nợ ai, chỉ ghi nhận để thống kê |
| Trả hộ — mỗi người một giá | Ăn sáng mỗi người một món, một người trả hộ | Mỗi người nợ đúng phần món của mình |

## Chạy local

```bash
./gradlew :webApp:wasmJsBrowserDevelopmentRun   # http://localhost:8080
```

## Chạy test

```bash
./gradlew :shared:jsTest
```

Dùng target `js` chứ không phải `wasmJs`: `:shared:wasmJsTest` hỏng trong
toolchain hiện tại (bundle Karma của target wasm lỗi
`Cannot use 'import.meta' outside a module` rồi báo "no tests discovered").
Toàn bộ code được test nằm trong `commonMain` nên chạy trên `js` là tương đương.
App vẫn ship bằng `wasmJs`.

## Deploy

```bash
./deploy.sh
```

Build bundle tại chỗ rồi đẩy file tĩnh lên Vercel — build image của Vercel không
có sẵn Gradle/JDK nên không build trên đó được. Cần `vercel login` một lần trước.

## Supabase

- Project ref: `gkqtbmidixjhrlxcgagh` (region `ap-southeast-1`)
- `supabase/migrations/` là nguồn sự thật của schema
- Đăng nhập bằng **tên + mật khẩu**. Supabase không có provider
  username/password nên tên được fold thành địa chỉ tổng hợp
  (`Lê Đức` → `le.duc@dalat.local`), xem `domain/AccountName.kt`.
- **Bắt buộc**: tắt *Confirm email* trong Auth → Providers → Email. Các địa chỉ
  `@dalat.local` không nhận được thư, nên nếu bật xác nhận thì signup sẽ fail.
- Quên mật khẩu thì reset tay trong dashboard (Authentication → Users), không có
  luồng khôi phục qua email.

Khoá anon nằm trong `shared/src/commonMain/kotlin/com/example/dalat/data/SupabaseClient.kt`
là cố ý — khoá đó thiết kế để nhúng vào client, RLS mới là thứ bảo vệ dữ liệu.
Khoá service role thì tuyệt đối không được đưa vào repo.

## Tài liệu

- Thiết kế: `docs/superpowers/specs/2026-09-16-dalat-trip-expense-splitter-design.md`
- Kế hoạch triển khai: `docs/superpowers/plans/2026-09-16-dalat-trip-expense-splitter.md`
