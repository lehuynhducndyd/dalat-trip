# Đà Lạt Trip Expense Splitter — Design Spec

Date: 2026-09-16
Project: `Dalat` (KMP Compose Multiplatform for Web, already scaffolded at repo root)

## 1. Goal

5 người bạn đi Đà Lạt 5 ngày. Mỗi người tự nhập chi tiêu của mình qua app trong
suốt chuyến đi, tất cả đồng bộ real-time. Cuối chuyến, app tính ra ai nợ ai bao
nhiêu và gợi ý cách chuyển khoản tối giản nhất để cân bằng sổ.

Không giới hạn cho một chuyến đi — thiết kế chung để tái dùng cho các chuyến đi
khác sau này (mỗi chuyến đi là một `trip` độc lập).

## 2. Non-goals (out of scope cho version đầu)

- Offline support (yêu cầu online-only, đã chốt với người dùng).
- Nhiều người cùng trả cho 1 khoản chi (multi-payer per expense).
- Đa tiền tệ (chỉ VND).
- OCR hoá đơn, tích hợp ngân hàng để tự xác nhận đã chuyển khoản.
- App native Android/iOS (chỉ Web, KMP giữ khả năng mở rộng sau này nhưng
  không build ở version này).

## 3. Auth & trip membership

- Đăng nhập bằng **Google OAuth** qua Supabase Auth. Không có tài khoản
  email/password riêng.
- Sau khi đăng nhập Google lần đầu, user hoặc tạo trip mới, hoặc nhập
  **Trip Code** để join trip đã có sẵn (trip code là cơ chế mời/join, không
  phải cơ chế xác thực — xác thực đã do Google lo).
- Một Google account có thể là thành viên của nhiều trip.
- `display_name` được đặt lúc tạo/join trip, mặc định điền sẵn từ Google profile
  (`full_name`) và sửa được ngay tại màn hình đó. Không có màn hình đổi tên về
  sau — với nhóm 5 người quen nhau thì không đáng làm.

## 4. Ba loại chi tiêu

1. **Group (chia đều)** — vd. khách sạn, thuê xe, lẩu/nướng cả nhóm ăn chung.
   Một người trả, số tiền chia đều cho danh sách người tham gia khoản đó
   (mặc định chọn sẵn cả 5 thành viên, bỏ chọn được nếu ai đó không tham gia
   khoản này).
2. **Personal – tự trả (self)** — vd. cà phê tự mua, đồ lặt vặt cá nhân.
   Không tạo công nợ với ai, chỉ ghi nhận để thống kê chi tiêu cá nhân.
3. **Personal – trả hộ theo item (itemized, unequal split)** — vd. ăn sáng cả
   nhóm nhưng mỗi người gọi món khác nhau, giá khác nhau; một người trả hộ cho
   tiện. Người trả nhập số tiền riêng cho từng người tham gia; mỗi người nợ
   đúng số tiền món của họ, không chia đều.

## 5. Data model (Postgres / Supabase)

```sql
-- Trips
create table trips (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  trip_code text not null unique,       -- short human-friendly join code, e.g. "DALAT926"
  start_date date not null,             -- lets the UI default the day picker to today's trip day
  day_count int not null default 5,
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now()
);

-- Membership: a Google user's participation in a trip
create table trip_members (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid not null references trips(id) on delete cascade,
  user_id uuid not null references auth.users(id),
  display_name text not null,
  joined_at timestamptz not null default now(),
  unique (trip_id, user_id)
);

-- Expenses
create type expense_type as enum ('group', 'personal_self', 'personal_itemized');

create table expenses (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid not null references trips(id) on delete cascade,
  payer_member_id uuid not null references trip_members(id),
  type expense_type not null,
  category text not null,               -- e.g. 'lodging', 'transport', 'group_meal', 'breakfast', 'drinks', 'other'
  amount bigint not null check (amount > 0), -- VND, integer đồng (no decimals)
  note text,
  trip_day int not null,                -- 1..day_count
  created_by uuid not null references auth.users(id),
  created_at timestamptz not null default now()
);

-- Per-member share of an expense.
-- - type = 'group': one row per participant, amount = total / participant_count (system-computed)
-- - type = 'personal_itemized': one row per participant, amount = entered manually by payer
-- - type = 'personal_self': single row, member = payer, amount = full amount (no debt, just for stats)
create table expense_shares (
  id uuid primary key default gen_random_uuid(),
  expense_id uuid not null references expenses(id) on delete cascade,
  member_id uuid not null references trip_members(id),
  amount bigint not null check (amount >= 0),
  unique (expense_id, member_id)
);

-- Tracks manual "marked as paid" state for a suggested settlement transaction.
-- Derived transactions are recomputed at read time; this table only stores the
-- settled flag, keyed by the deterministic (from_member, to_member) pair per trip.
create table settlement_marks (
  trip_id uuid not null references trips(id) on delete cascade,
  from_member_id uuid not null references trip_members(id),
  to_member_id uuid not null references trip_members(id),
  is_paid boolean not null default false,
  paid_at timestamptz,
  primary key (trip_id, from_member_id, to_member_id)
);
```

### RLS policy design

Every table has RLS enabled. Two `SECURITY DEFINER` helpers carry the weight:

- **`is_trip_member(p_trip_id uuid) returns boolean`** — the membership test used
  by every policy. It MUST be `SECURITY DEFINER`: a policy on `trip_members`
  that queries `trip_members` directly causes infinite RLS recursion, and
  `SECURITY DEFINER` bypasses RLS inside the function body, breaking the cycle.
- **`join_trip(p_trip_code text, p_display_name text) returns uuid`** — resolves
  a trip code to a trip and inserts the caller's `trip_members` row. Needed
  because of a chicken-and-egg problem: a user must read a trip to join it, but
  the read policy requires already being a member. Routing the join through a
  `SECURITY DEFINER` RPC avoids having to make the whole `trips` table
  world-readable just so codes can be looked up.

Policies:

- `trips`: SELECT where `is_trip_member(id)`; INSERT by any authenticated user
  (`created_by = auth.uid()`); UPDATE/DELETE only by `created_by`.
- `trip_members`: SELECT where `is_trip_member(trip_id)`; INSERT only via
  `join_trip` / trip creation; UPDATE only own row (`user_id = auth.uid()`).
- `expenses`, `expense_shares`, `settlement_marks`: SELECT/INSERT where
  `is_trip_member(trip_id)`; UPDATE/DELETE on `expenses` additionally require
  `created_by = auth.uid()`. `expense_shares` derives its trip from its parent
  expense.
- Realtime: `expenses`, `expense_shares` and `settlement_marks` are added to the
  `supabase_realtime` publication so clients get change events; RLS still
  applies to the streamed rows.

## 6. Settlement algorithm

1. For every expense, use its `expense_shares` rows to compute, per member,
   `net_balance = total_paid_by_them - total_owed_by_them` across the whole
   trip (payer's own share nets against what they paid; `personal_self`
   expenses don't affect any balance since payer == the only share).
2. This yields one net number per member (positive = owed money, negative =
   owes money). Sum of all net balances across members is always 0.
3. **Minimal transaction suggestion:** greedy min-cash-flow — repeatedly match
   the member with the largest positive balance against the member with the
   largest negative balance, create a transaction for `min(|creditor|,
   |debtor|)`, reduce both, repeat until all balances are ~0. This bounds the
   number of suggested transactions to at most N-1 (N = member count).
4. Settlement screen shows both:
   - **Simplified view**: the minimal transaction list from step 3, each with
     a "mark as paid" checkbox (writes to `settlement_marks`).
   - **Detailed view**: full per-expense breakdown per member, for anyone who
     wants to audit the numbers behind the simplified suggestion.

## 7. Features / Screens (mobile-first)

- **Sign in** — Google OAuth button.
- **Trip picker** — list of trips the user belongs to, "Create trip" /
  "Join with code" actions.
- **Expense feed** (default tab after entering a trip) — reverse-chronological
  list, real-time via Supabase Realtime subscription on `expenses`, with a day
  filter. FAB to add expense. Category and type breakdowns live on the
  dashboard instead of duplicating them as feed filters.
- **Add expense** — pick type (group / personal self / personal itemized),
  category, day, amount; for group pick participants (chips, default all); for
  itemized enter a per-person amount and let the total be the sum of them.
- **Delete expense** — only the creator can delete their own entries. There is
  deliberately no edit screen: correcting an entry means deleting and re-adding
  it, which avoids a second code path that has to keep an expense and its
  shares reconciled.
- **Dashboard** — total trip spend, spend by day, spend by category, spend by
  person (paid vs. owed).
- **Settle up** — simplified transaction list + detailed breakdown, as in §6.

Layout: single column, bottom navigation (Feed / Dashboard / Settle up), large
touch targets, currency formatted as VND with thousands separators.

## 8. Tech architecture

- **Frontend**: existing KMP scaffold (Kotlin 2.4.20, Compose Multiplatform
  1.12.0, targets `js` + `wasmJs` only). `shared` module holds Compose UI +
  business logic (split/settlement calc, models); `webApp` module is the Web
  entry point. Ship the **wasmJs** target
  (`wasmJsBrowserDevelopmentRun` / `wasmJsBrowserDistribution`). Because `js`
  and `wasmJs` are the only targets, all dependencies live in `commonMain`.
- **Verified dependency stack**: `supabase-kt` 3.8.0 publishes `-wasm-js`
  artifacts for `supabase-kt`, `auth-kt`, `postgrest-kt` and `realtime-kt`, and
  pulls Ktor 3.5.1; `ktor-client-js` has a `wasm-js` variant that supports
  WebSockets (required by Realtime). No JS fallback needed.
- **Backend**: Supabase project — Postgres (schema in §5), Row Level Security
  (policies in §5), Realtime (broadcast on `expenses`, `settlement_marks`),
  Auth (Google OAuth provider enabled in Supabase dashboard). Accessed from
  Kotlin via the `supabase-kt` client library (Postgrest, Auth, Realtime
  modules) — no custom backend server needed.
- **Hosting**: **Vercel as a pure static host, deployed prebuilt.** Vercel's
  build image is Node-oriented and has no supported Gradle/JDK toolchain, so
  the Kotlin/Wasm bundle is built locally (or in CI) with
  `./gradlew :webApp:wasmJsBrowserDistribution` and the resulting directory is
  deployed with `vercel deploy --prebuilt` (or `vercel deploy` from the output
  directory). A `vercel.json` supplies the SPA rewrite and the
  `application/wasm` content type.

## 9. Open risks to verify during implementation

- **Auth session persistence on wasmJs** — confirm that after signing in with
  Google and reloading the page, the session is restored. If supabase-kt's
  default session manager does not persist on wasmJs, implement a custom
  `SessionManager` backed by `window.localStorage`.
- **Google OAuth redirect flow in a static SPA** — no server-side callback route
  exists, so the Supabase "Site URL" and "Redirect URLs" must list both the
  local dev origin and the deployed Vercel origin, and the PKCE code exchange
  must complete client-side on page load.
- **Realtime under RLS** — verify change events actually arrive for a second
  signed-in member, not just the row's author.
- **Trip code collision** on trip creation — retry with a new random code on
  unique-constraint violation.
