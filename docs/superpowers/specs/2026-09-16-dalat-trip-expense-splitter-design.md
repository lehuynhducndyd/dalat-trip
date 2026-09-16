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
- `display_name` mặc định lấy từ Google profile (`full_name`), cho phép sửa
  riêng theo từng trip (vd. để hiển thị biệt danh).

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
  amount numeric(12,0) not null check (amount > 0), -- VND, no decimals
  note text,
  trip_day int not null,                -- 1..5
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
  amount numeric(12,0) not null check (amount >= 0),
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

- `trips`: readable/insertable by any authenticated user; only `created_by` can
  update/delete.
- `trip_members`: readable by any member of the same trip; a user can insert
  their own membership row (self-join via trip code, validated in app logic
  before insert); only the row's own `user_id` can update their `display_name`.
- `expenses`, `expense_shares`, `settlement_marks`: readable/writable only by
  users whose `auth.uid()` has a matching row in `trip_members` for that
  `trip_id`. Update/delete on `expenses` restricted to `created_by = auth.uid()`.

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
  list, real-time via Supabase Realtime subscription on `expenses`, filter by
  day/category/type. FAB to add expense.
- **Add/Edit expense** — pick type (group / personal self / personal
  itemized), category, day, amount; for group pick participants (checkboxes,
  default all); for itemized enter per-person amount; only creator can
  edit/delete their own entries.
- **Dashboard** — total trip spend, spend by day, spend by category, spend by
  person (paid vs. owed).
- **Settle up** — simplified transaction list + detailed breakdown, as in §6.

Layout: single column, bottom navigation (Feed / Dashboard / Settle up), large
touch targets, currency formatted as VND with thousands separators.

## 8. Tech architecture

- **Frontend**: existing KMP scaffold. `shared` module holds Compose UI +
  business logic (split/settlement calc, models); `webApp` module is the Web
  entry point. Prefer **wasmJs** target (`wasmJsBrowserDevelopmentRun` /
  `wasmJsBrowserDistribution`); fall back to **js** target if a required
  dependency (notably the Supabase Kotlin SDK) lacks stable wasmJs support —
  verify this early, it is the main technical risk in this plan.
- **Backend**: Supabase project — Postgres (schema in §5), Row Level Security
  (policies in §5), Realtime (broadcast on `expenses`, `settlement_marks`),
  Auth (Google OAuth provider enabled in Supabase dashboard). Accessed from
  Kotlin via the `supabase-kt` client library (Postgrest, Auth, Realtime
  modules) — no custom backend server needed.
- **Hosting**: static build output deployed to **Vercel** as a static site
  (`vercel.json` with the wasmJs/js distribution output directory).

## 9. Open risks to verify during implementation

- `supabase-kt` wasmJs target maturity — check before committing to wasmJs;
  fall back to jsMain if broken.
- Google OAuth redirect flow in a Kotlin/Wasm SPA hosted on Vercel — confirm
  Supabase Auth redirect URLs work with the static hosting setup (no
  server-side callback route available).
- Trip code collision handling on trip creation (retry with a new random code
  on unique-constraint violation).
