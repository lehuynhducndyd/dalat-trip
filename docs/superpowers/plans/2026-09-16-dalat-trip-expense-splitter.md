# Đà Lạt Trip Expense Splitter — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A mobile-first web app where 5 friends on a 5-day Đà Lạt trip log shared and personal expenses in real time, and get a minimal list of who-pays-whom at the end.

**Architecture:** Compose Multiplatform for Web (Kotlin/Wasm) single-page app talking directly to Supabase — no custom backend. All money math lives in pure, unit-tested Kotlin functions in `shared/commonMain/.../domain/`; Supabase I/O is isolated behind repositories; one `TripViewModel` per trip owns all loaded state and Realtime refresh, and the three tab screens are pure renderers over it.

**Tech Stack:** Kotlin 2.4.20, Compose Multiplatform 1.12.0, supabase-kt 3.8.0 (auth/postgrest/realtime), Ktor 3.5.1 (`ktor-client-js`), kotlinx-serialization 1.11.0, kotlinx-datetime 0.8.0, Supabase (Postgres + RLS + Realtime + Google OAuth), Vercel static hosting.

**Spec:** `docs/superpowers/specs/2026-09-16-dalat-trip-expense-splitter-design.md` — read it before Task 1 and keep it open; this plan argues from it.

## Global Constraints

- Kotlin `2.4.20`, Compose Multiplatform `1.12.0`, Material3 `1.12.0-alpha03` — already in `gradle/libs.versions.toml`, do not change.
- Targets are `js` and `wasmJs` **only**. There is no JVM/Android/iOS target, so every dependency goes in `commonMain.dependencies` and there is no `androidMain`/`iosMain` to write.
- Ship target is `wasmJs`. Dev run: `./gradlew :webApp:wasmJsBrowserDevelopmentRun`. Production bundle: `./gradlew :webApp:wasmJsBrowserDistribution`.
- supabase-kt version `3.8.0`, Ktor version `3.5.1`. Keep them in lockstep — supabase-kt 3.8.0 is built against Ktor 3.5.1.
- Package root is `com.example.dalat`. Existing scaffold files live there.
- Money is **VND as `Long` đồng** everywhere — Kotlin `Long`, Postgres `bigint`. Never `Double`, never `Float`. Splits must reconcile to the đồng: the sum of an expense's shares must exactly equal the expense amount.
- UI copy is Vietnamese. Currency renders as `1.234.567 ₫` (dot thousands separators).
- Tests are `kotlin-test` in `shared/src/commonTest/`, run with **`./gradlew :shared:jsTest`**.
  `:shared:wasmJsTest` is broken in this toolchain — the Karma bundle for the wasm
  target dies with `Uncaught SyntaxError: Cannot use 'import.meta' outside a module`
  and reports "no tests discovered". Everything under test is pure `commonMain`
  Kotlin, so running it on the `js` target proves the same code; the app still
  *ships* as wasmJs, which compiles and bundles fine. Wherever a task below says
  `:shared:wasmJsTest --tests "*Foo*"`, run `:shared:jsTest --tests "*Foo*"`.
- Never commit real Supabase keys before Task 2 decides where they live. The anon/publishable key is safe in client code (RLS protects the data); the **service role key must never appear in this repo**.
- Commit after every task. Conventional commit prefixes (`feat:`, `test:`, `chore:`).

---

## File Structure

**Created in `shared/src/commonMain/kotlin/com/example/dalat/`:**

| Path | Responsibility |
|---|---|
| `model/Models.kt` | `@Serializable` row + insert DTOs mirroring the Postgres schema, `ExpenseType`, `Category` |
| `domain/Money.kt` | `formatVnd` — VND rendering |
| `domain/SplitCalculator.kt` | Equal split with exact đồng remainder distribution |
| `domain/Balances.kt` | `MemberBalance`, `BalanceCalculator` — paid/owed/net per member |
| `domain/Settlement.kt` | `SettlementTransaction`, `SettlementCalculator` — greedy min-cash-flow |
| `domain/TripStats.kt` | Dashboard aggregations (by day, by category, by member) |
| `data/SupabaseClient.kt` | The `SupabaseClient` singleton + URL/anon key config |
| `data/AuthRepository.kt` | Google sign-in/out, session status, current user id |
| `data/TripRepository.kt` | `create_trip` / `join_trip` RPCs, list trips, load members |
| `data/ExpenseRepository.kt` | Expense + share CRUD, settlement marks, Realtime change flow |
| `ui/Theme.kt` | Material3 color scheme + typography |
| `ui/components/Common.kt` | `MoneyText`, `SectionCard`, `MemberChip`, `AmountField` |
| `ui/SignInScreen.kt` | Google sign-in screen |
| `ui/TripPickerScreen.kt` | Create trip / join by code |
| `ui/TripViewModel.kt` | Owns all state for one trip; Realtime-driven refresh |
| `ui/TripHomeScreen.kt` | Scaffold + bottom nav hosting the three tabs |
| `ui/FeedScreen.kt` | Expense list |
| `ui/AddExpenseScreen.kt` | Add/edit expense form (all three expense types) |
| `ui/DashboardScreen.kt` | Totals by day/category/member |
| `ui/SettleUpScreen.kt` | Net balances + minimal transactions + detail breakdown |

**Modified:** `gradle/libs.versions.toml`, `shared/build.gradle.kts`, `shared/src/commonMain/kotlin/com/example/dalat/App.kt`, `webApp/src/webMain/resources/index.html`.

**Deleted once unused:** `Greeting.kt`, `GreetingUtil.kt`, `Platform.kt`, `Platform.js.kt`, `Platform.wasmJs.kt`, `SharedCommonTest.kt`, `SharedLogicWebTest.kt` (Task 11).

---

## Task 1: Supabase project, schema, RLS and Realtime

This task retires the riskiest backend assumptions (RLS recursion, join-by-code, Realtime under RLS) before any Kotlin is written.

**Tools:** Use the Supabase MCP tools (`mcp__plugin_supabase_supabase__*`). They are available in this session.

**Files:**
- Create: `supabase/migrations/0001_init.sql` (kept in the repo as the source of truth, applied via MCP)

- [ ] **Step 1: Pick or create the Supabase project**

Run `list_organizations`, then `list_projects`. If a suitable project already exists, use it and record its `id`. Otherwise `create_project` with name `dalat-trip`, region closest to Vietnam (`ap-southeast-1`).

**STOP and ask the user** which organization/project to use if more than one exists — do not create billable resources without confirmation.

- [ ] **Step 2: Write the migration file**

Create `supabase/migrations/0001_init.sql`:

```sql
-- Đà Lạt trip expense splitter: initial schema.

create extension if not exists pgcrypto;

create type expense_type as enum ('group', 'personal_self', 'personal_itemized');

create table trips (
  id uuid primary key default gen_random_uuid(),
  name text not null,
  trip_code text not null unique,
  start_date date not null,
  day_count int not null default 5 check (day_count between 1 and 60),
  created_by uuid not null references auth.users(id) default auth.uid(),
  created_at timestamptz not null default now()
);

create table trip_members (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid not null references trips(id) on delete cascade,
  user_id uuid not null references auth.users(id) default auth.uid(),
  display_name text not null,
  joined_at timestamptz not null default now(),
  unique (trip_id, user_id)
);

create table expenses (
  id uuid primary key default gen_random_uuid(),
  trip_id uuid not null references trips(id) on delete cascade,
  payer_member_id uuid not null references trip_members(id),
  type expense_type not null,
  category text not null,
  amount bigint not null check (amount > 0),
  note text,
  trip_day int not null check (trip_day >= 1),
  created_by uuid not null references auth.users(id) default auth.uid(),
  created_at timestamptz not null default now()
);

create index expenses_trip_created_idx on expenses (trip_id, created_at desc);

-- trip_id is denormalised here so RLS policies and Realtime filters can work
-- on this table without joining back to expenses.
create table expense_shares (
  id uuid primary key default gen_random_uuid(),
  expense_id uuid not null references expenses(id) on delete cascade,
  trip_id uuid not null references trips(id) on delete cascade,
  member_id uuid not null references trip_members(id),
  amount bigint not null check (amount >= 0),
  unique (expense_id, member_id)
);

create index expense_shares_trip_idx on expense_shares (trip_id);

create table settlement_marks (
  trip_id uuid not null references trips(id) on delete cascade,
  from_member_id uuid not null references trip_members(id),
  to_member_id uuid not null references trip_members(id),
  is_paid boolean not null default false,
  paid_at timestamptz,
  primary key (trip_id, from_member_id, to_member_id)
);

-- SECURITY DEFINER is load-bearing: a policy on trip_members that queries
-- trip_members directly recurses forever. Running the lookup as the definer
-- bypasses RLS inside the body and breaks the cycle.
create or replace function public.is_trip_member(p_trip_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
  select exists (
    select 1 from public.trip_members
    where trip_id = p_trip_id and user_id = auth.uid()
  );
$$;

-- Creating a trip and its first membership row must be atomic, and the caller
-- cannot insert into trips directly (no INSERT policy).
create or replace function public.create_trip(
  p_name text,
  p_start_date date,
  p_day_count int,
  p_display_name text
) returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_trip_id uuid;
  v_code text;
  v_attempt int := 0;
begin
  if auth.uid() is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  loop
    v_attempt := v_attempt + 1;
    v_code := upper(substr(replace(gen_random_uuid()::text, '-', ''), 1, 6));
    begin
      insert into trips (name, trip_code, start_date, day_count, created_by)
      values (p_name, v_code, p_start_date, p_day_count, auth.uid())
      returning id into v_trip_id;
      exit;
    exception when unique_violation then
      if v_attempt >= 10 then
        raise exception 'TRIP_CODE_GENERATION_FAILED';
      end if;
    end;
  end loop;

  insert into trip_members (trip_id, user_id, display_name)
  values (v_trip_id, auth.uid(), p_display_name);

  return v_trip_id;
end;
$$;

-- Chicken-and-egg: you must read a trip to join it, but the read policy
-- requires membership. This RPC resolves the code as definer instead of
-- making the whole trips table world-readable.
create or replace function public.join_trip(p_trip_code text, p_display_name text)
returns uuid
language plpgsql
security definer
set search_path = public
as $$
declare
  v_trip_id uuid;
begin
  if auth.uid() is null then
    raise exception 'NOT_AUTHENTICATED';
  end if;

  select id into v_trip_id from trips where trip_code = upper(trim(p_trip_code));
  if v_trip_id is null then
    raise exception 'TRIP_NOT_FOUND';
  end if;

  insert into trip_members (trip_id, user_id, display_name)
  values (v_trip_id, auth.uid(), p_display_name)
  on conflict (trip_id, user_id) do update set display_name = excluded.display_name;

  return v_trip_id;
end;
$$;

revoke all on function public.is_trip_member(uuid) from public;
revoke all on function public.create_trip(text, date, int, text) from public;
revoke all on function public.join_trip(text, text) from public;
grant execute on function public.is_trip_member(uuid) to authenticated;
grant execute on function public.create_trip(text, date, int, text) to authenticated;
grant execute on function public.join_trip(text, text) to authenticated;

alter table trips enable row level security;
alter table trip_members enable row level security;
alter table expenses enable row level security;
alter table expense_shares enable row level security;
alter table settlement_marks enable row level security;

-- trips: no INSERT policy on purpose — creation goes through create_trip().
create policy trips_select on trips for select to authenticated
  using (public.is_trip_member(id));
create policy trips_update on trips for update to authenticated
  using (created_by = auth.uid()) with check (created_by = auth.uid());
create policy trips_delete on trips for delete to authenticated
  using (created_by = auth.uid());

-- trip_members: no INSERT policy — membership goes through create_trip()/join_trip().
create policy trip_members_select on trip_members for select to authenticated
  using (public.is_trip_member(trip_id));
create policy trip_members_update on trip_members for update to authenticated
  using (user_id = auth.uid()) with check (user_id = auth.uid());

create policy expenses_select on expenses for select to authenticated
  using (public.is_trip_member(trip_id));
create policy expenses_insert on expenses for insert to authenticated
  with check (public.is_trip_member(trip_id) and created_by = auth.uid());
create policy expenses_update on expenses for update to authenticated
  using (created_by = auth.uid()) with check (created_by = auth.uid());
create policy expenses_delete on expenses for delete to authenticated
  using (created_by = auth.uid());

-- Shares are readable by the whole trip but writable only by whoever owns the
-- parent expense, so nobody can quietly rewrite someone else's split.
create policy expense_shares_select on expense_shares for select to authenticated
  using (public.is_trip_member(trip_id));
create policy expense_shares_insert on expense_shares for insert to authenticated
  with check (
    public.is_trip_member(trip_id)
    and exists (select 1 from expenses e where e.id = expense_id and e.created_by = auth.uid())
  );
create policy expense_shares_update on expense_shares for update to authenticated
  using (exists (select 1 from expenses e where e.id = expense_id and e.created_by = auth.uid()))
  with check (exists (select 1 from expenses e where e.id = expense_id and e.created_by = auth.uid()));
create policy expense_shares_delete on expense_shares for delete to authenticated
  using (exists (select 1 from expenses e where e.id = expense_id and e.created_by = auth.uid()));

create policy settlement_marks_select on settlement_marks for select to authenticated
  using (public.is_trip_member(trip_id));
create policy settlement_marks_write on settlement_marks for all to authenticated
  using (public.is_trip_member(trip_id)) with check (public.is_trip_member(trip_id));

-- Realtime. REPLICA IDENTITY FULL is required for DELETE events to carry the
-- old row, which is what filtered subscriptions match against.
alter table expenses replica identity full;
alter table expense_shares replica identity full;
alter table settlement_marks replica identity full;

alter publication supabase_realtime add table expenses;
alter publication supabase_realtime add table expense_shares;
alter publication supabase_realtime add table settlement_marks;
```

- [ ] **Step 3: Apply the migration**

Call `apply_migration` with name `0001_init` and the SQL above.

- [ ] **Step 4: Verify the schema landed**

Call `execute_sql` with:

```sql
select tablename, rowsecurity
from pg_tables
where schemaname = 'public'
order by tablename;
```

Expected: 5 rows (`expense_shares`, `expenses`, `settlement_marks`, `trip_members`, `trips`), every `rowsecurity` = `true`.

Then:

```sql
select tablename, count(*) as policies
from pg_policies
where schemaname = 'public'
group by tablename
order by tablename;
```

Expected: `expense_shares` 4, `expenses` 4, `settlement_marks` 2, `trip_members` 2, `trips` 3.

Then confirm Realtime:

```sql
select tablename from pg_publication_tables
where pubname = 'supabase_realtime' order by tablename;
```

Expected: `expense_shares`, `expenses`, `settlement_marks`.

- [ ] **Step 5: Check security advisors**

Call `get_advisors` with type `security`. Expected: no ERROR-level findings about tables without RLS. Function search_path warnings should not appear because every function sets `search_path`. Fix anything ERROR-level before continuing.

- [ ] **Step 6: Record the project credentials**

Call `get_project_url` and `get_publishable_keys`. Write both values down — Task 2 hardcodes them into `SupabaseConfig.kt`. Do **not** fetch or store the service role key.

- [ ] **Step 7: Human gate — enable Google sign-in**

This cannot be done through MCP. Tell the user to do it now and wait for confirmation:

1. Google Cloud Console → *APIs & Services* → *Credentials* → *Create OAuth client ID* → type **Web application**.
2. Authorized redirect URI: `<SUPABASE_URL>/auth/v1/callback` (the URL from Step 6).
3. Copy the Client ID and Client Secret into Supabase Dashboard → *Authentication* → *Providers* → *Google* → enable, paste, save.
4. Supabase Dashboard → *Authentication* → *URL Configuration*: set **Site URL** to `http://localhost:8080` for now, and add `http://localhost:8080/**` to **Redirect URLs**. The Vercel origin gets added in Task 17.

- [ ] **Step 8: Commit**

```bash
git add supabase/migrations/0001_init.sql
git commit -m "feat: add Supabase schema, RLS policies and Realtime publication"
```

---

## Task 2: Dependencies and Supabase client

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `shared/build.gradle.kts`
- Create: `shared/src/commonMain/kotlin/com/example/dalat/data/SupabaseClient.kt`

**Interfaces:**
- Produces: `com.example.dalat.data.supabase` — a lazily-created `SupabaseClient` with `Auth`, `Postgrest` and `Realtime` installed, used by every repository.

- [ ] **Step 1: Add version catalog entries**

In `gradle/libs.versions.toml`, add to `[versions]`:

```toml
supabase = "3.8.0"
ktor = "3.5.1"
kotlinxSerialization = "1.11.0"
kotlinxDatetime = "0.8.0"
kotlinxCoroutines = "1.11.0"
```

Add to `[libraries]`:

```toml
supabase-auth = { module = "io.github.jan-tennert.supabase:auth-kt", version.ref = "supabase" }
supabase-postgrest = { module = "io.github.jan-tennert.supabase:postgrest-kt", version.ref = "supabase" }
supabase-realtime = { module = "io.github.jan-tennert.supabase:realtime-kt", version.ref = "supabase" }
ktor-client-js = { module = "io.ktor:ktor-client-js", version.ref = "ktor" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinxSerialization" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "kotlinxDatetime" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinxCoroutines" }
```

Add to `[plugins]`:

```toml
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Wire the dependencies into `shared/build.gradle.kts`**

Add `alias(libs.plugins.kotlinSerialization)` to the `plugins { }` block, and add these lines inside the existing `commonMain.dependencies { }` block (keep everything already there):

```kotlin
implementation(libs.supabase.auth)
implementation(libs.supabase.postgrest)
implementation(libs.supabase.realtime)
implementation(libs.ktor.client.js)
implementation(libs.kotlinx.serialization.json)
implementation(libs.kotlinx.datetime)
implementation(libs.kotlinx.coroutines.core)
```

`ktor-client-js` publishes both `js` and `wasm-js` variants, so one declaration in `commonMain` covers both targets.

- [ ] **Step 3: Create the client**

Create `shared/src/commonMain/kotlin/com/example/dalat/data/SupabaseClient.kt`, substituting the two values recorded in Task 1 Step 6:

```kotlin
package com.example.dalat.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.FlowType
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.serializer.KotlinXSerializer
import kotlinx.serialization.json.Json

// The publishable (anon) key is designed to ship in browser code; RLS is what
// protects the data. The service role key must never appear in this repo.
private const val SUPABASE_URL = "https://PROJECT_REF.supabase.co"
private const val SUPABASE_ANON_KEY = "PASTE_PUBLISHABLE_KEY_HERE"

val supabase: SupabaseClient by lazy {
    createSupabaseClient(
        supabaseUrl = SUPABASE_URL,
        supabaseKey = SUPABASE_ANON_KEY,
    ) {
        // Tables return columns the Kotlin models deliberately omit (created_at,
        // joined_at, ...). Without ignoreUnknownKeys every SELECT would throw.
        defaultSerializer = KotlinXSerializer(Json { ignoreUnknownKeys = true })

        install(Auth) {
            flowType = FlowType.PKCE
        }
        install(Postgrest)
        install(Realtime)
    }
}
```

- [ ] **Step 4: Verify it compiles for wasmJs**

Run: `./gradlew :shared:compileKotlinWasmJs`
Expected: `BUILD SUCCESSFUL`.

If an import does not resolve, check the actual package names in the resolved jar rather than guessing — the Maven group is `io.github.jan-tennert.supabase` but the Kotlin packages are `io.github.jan.supabase.*`.

- [ ] **Step 5: Verify the app still runs**

Run: `./gradlew :webApp:wasmJsBrowserDevelopmentRun`
Open `http://localhost:8080`. Expected: the scaffold's "Click me!" screen still renders, no console errors about missing modules. Stop the server.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml shared/build.gradle.kts shared/src/commonMain/kotlin/com/example/dalat/data/SupabaseClient.kt
git commit -m "feat: add supabase-kt and configure the Supabase client"
```

---

## Task 3: Domain models and VND formatting

**Files:**
- Create: `shared/src/commonMain/kotlin/com/example/dalat/model/Models.kt`
- Create: `shared/src/commonMain/kotlin/com/example/dalat/domain/Money.kt`
- Test: `shared/src/commonTest/kotlin/com/example/dalat/domain/MoneyTest.kt`

**Interfaces:**
- Produces: `Trip`, `TripMember`, `Expense`, `ExpenseShare`, `SettlementMark`, `NewExpense`, `NewExpenseShare`, `ExpenseType`, `Category`, and `formatVnd(amount: Long): String`. Every later task consumes these exact names.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/example/dalat/domain/MoneyTest.kt`:

```kotlin
package com.example.dalat.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class MoneyTest {
    @Test
    fun formatsMillionsWithDotSeparators() {
        assertEquals("1.234.567 ₫", formatVnd(1_234_567))
    }

    @Test
    fun formatsSmallAmountsWithoutSeparators() {
        assertEquals("500 ₫", formatVnd(500))
    }

    @Test
    fun formatsZero() {
        assertEquals("0 ₫", formatVnd(0))
    }

    @Test
    fun formatsExactThousand() {
        assertEquals("1.000 ₫", formatVnd(1_000))
    }

    @Test
    fun formatsNegativeAmounts() {
        assertEquals("-5.000 ₫", formatVnd(-5_000))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:wasmJsTest --tests "*MoneyTest*"`
Expected: FAIL — unresolved reference `formatVnd`.

- [ ] **Step 3: Implement `formatVnd`**

Create `shared/src/commonMain/kotlin/com/example/dalat/domain/Money.kt`:

```kotlin
package com.example.dalat.domain

fun formatVnd(amount: Long): String {
    val sign = if (amount < 0) "-" else ""
    val digits = if (amount < 0) (-amount).toString() else amount.toString()
    val grouped = digits.reversed().chunked(3).joinToString(".").reversed()
    return "$sign$grouped ₫"
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:wasmJsTest --tests "*MoneyTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Write the models**

Create `shared/src/commonMain/kotlin/com/example/dalat/model/Models.kt`. Column names are snake_case in Postgres and camelCase in Kotlin, so every mismatched field needs `@SerialName`:

```kotlin
package com.example.dalat.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ExpenseType {
    @SerialName("group") GROUP,
    @SerialName("personal_self") PERSONAL_SELF,
    @SerialName("personal_itemized") PERSONAL_ITEMIZED,
}

enum class Category(val id: String, val label: String) {
    LODGING("lodging", "Chỗ ở"),
    TRANSPORT("transport", "Di chuyển"),
    GROUP_MEAL("group_meal", "Ăn nhóm"),
    BREAKFAST("breakfast", "Ăn sáng"),
    DRINKS("drinks", "Nước / Cà phê"),
    SNACKS("snacks", "Đồ ăn vặt"),
    TICKETS("tickets", "Vé / Tham quan"),
    OTHER("other", "Khác");

    companion object {
        fun fromId(id: String): Category = entries.firstOrNull { it.id == id } ?: OTHER
    }
}

@Serializable
data class Trip(
    val id: String,
    val name: String,
    @SerialName("trip_code") val tripCode: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("day_count") val dayCount: Int,
    @SerialName("created_by") val createdBy: String,
)

@Serializable
data class TripMember(
    val id: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
)

@Serializable
data class Expense(
    val id: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("payer_member_id") val payerMemberId: String,
    val type: ExpenseType,
    val category: String,
    val amount: Long,
    val note: String? = null,
    @SerialName("trip_day") val tripDay: Int,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
)

@Serializable
data class ExpenseShare(
    val id: String,
    @SerialName("expense_id") val expenseId: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("member_id") val memberId: String,
    val amount: Long,
)

@Serializable
data class SettlementMark(
    @SerialName("trip_id") val tripId: String,
    @SerialName("from_member_id") val fromMemberId: String,
    @SerialName("to_member_id") val toMemberId: String,
    @SerialName("is_paid") val isPaid: Boolean,
)

// Insert DTOs omit server-generated columns (id, created_at, created_by) so
// Postgres defaults apply.
@Serializable
data class NewExpense(
    @SerialName("trip_id") val tripId: String,
    @SerialName("payer_member_id") val payerMemberId: String,
    val type: ExpenseType,
    val category: String,
    val amount: Long,
    val note: String?,
    @SerialName("trip_day") val tripDay: Int,
)

@Serializable
data class NewExpenseShare(
    @SerialName("expense_id") val expenseId: String,
    @SerialName("trip_id") val tripId: String,
    @SerialName("member_id") val memberId: String,
    val amount: Long,
)
```

- [ ] **Step 6: Verify everything compiles**

Run: `./gradlew :shared:compileKotlinWasmJs`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/model shared/src/commonMain/kotlin/com/example/dalat/domain shared/src/commonTest/kotlin/com/example/dalat/domain
git commit -m "feat: add expense domain models and VND formatting"
```

---

## Task 4: Equal-split calculator

The core correctness requirement: 100.000 ₫ split three ways must produce shares that sum back to exactly 100.000 ₫, not 99.999 ₫.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/example/dalat/domain/SplitCalculator.kt`
- Test: `shared/src/commonTest/kotlin/com/example/dalat/domain/SplitCalculatorTest.kt`

**Interfaces:**
- Produces: `SplitCalculator.splitEqually(total: Long, memberIds: List<String>): Map<String, Long>`. Task 14 (add expense) and Task 9 (expense repository) both call it.

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/example/dalat/domain/SplitCalculatorTest.kt`:

```kotlin
package com.example.dalat.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SplitCalculatorTest {
    @Test
    fun splitsEvenlyWhenDivisible() {
        val result = SplitCalculator.splitEqually(900_000, listOf("a", "b", "c"))
        assertEquals(mapOf("a" to 300_000L, "b" to 300_000L, "c" to 300_000L), result)
    }

    @Test
    fun distributesRemainderToEarliestMembers() {
        val result = SplitCalculator.splitEqually(100_000, listOf("a", "b", "c"))
        assertEquals(mapOf("a" to 33_334L, "b" to 33_333L, "c" to 33_333L), result)
    }

    @Test
    fun sharesAlwaysSumToTotal() {
        for (total in listOf(1L, 7L, 999L, 100_000L, 1_234_567L)) {
            val result = SplitCalculator.splitEqually(total, listOf("a", "b", "c", "d", "e"))
            assertEquals(total, result.values.sum(), "total=$total must reconcile")
        }
    }

    @Test
    fun singleMemberTakesEverything() {
        assertEquals(mapOf("a" to 50_000L), SplitCalculator.splitEqually(50_000, listOf("a")))
    }

    @Test
    fun rejectsEmptyParticipantList() {
        assertFailsWith<IllegalArgumentException> {
            SplitCalculator.splitEqually(1_000, emptyList())
        }
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:wasmJsTest --tests "*SplitCalculatorTest*"`
Expected: FAIL — unresolved reference `SplitCalculator`.

- [ ] **Step 3: Implement the calculator**

Create `shared/src/commonMain/kotlin/com/example/dalat/domain/SplitCalculator.kt`:

```kotlin
package com.example.dalat.domain

object SplitCalculator {
    fun splitEqually(total: Long, memberIds: List<String>): Map<String, Long> {
        require(memberIds.isNotEmpty()) { "Cần ít nhất một người tham gia" }
        val base = total / memberIds.size
        val remainder = (total % memberIds.size).toInt()
        return memberIds.mapIndexed { index, id ->
            id to if (index < remainder) base + 1 else base
        }.toMap()
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :shared:wasmJsTest --tests "*SplitCalculatorTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/domain/SplitCalculator.kt shared/src/commonTest/kotlin/com/example/dalat/domain/SplitCalculatorTest.kt
git commit -m "feat: add exact equal-split calculator"
```

---

## Task 5: Balances and settlement simplification

**Files:**
- Create: `shared/src/commonMain/kotlin/com/example/dalat/domain/Balances.kt`
- Create: `shared/src/commonMain/kotlin/com/example/dalat/domain/Settlement.kt`
- Test: `shared/src/commonTest/kotlin/com/example/dalat/domain/BalancesTest.kt`
- Test: `shared/src/commonTest/kotlin/com/example/dalat/domain/SettlementTest.kt`

**Interfaces:**
- Produces:
  - `data class MemberBalance(val memberId: String, val paid: Long, val owed: Long)` with `val net: Long`
  - `BalanceCalculator.compute(memberIds: List<String>, expenses: List<Expense>, shares: List<ExpenseShare>): List<MemberBalance>`
  - `data class SettlementTransaction(val fromMemberId: String, val toMemberId: String, val amount: Long)`
  - `SettlementCalculator.simplify(balances: List<MemberBalance>): List<SettlementTransaction>`

`personal_self` expenses need no special case: their single share belongs to the payer, so paid and owed cancel and `net` is unaffected, while `paid` still reflects real cash out of pocket.

- [ ] **Step 1: Write the failing balance test**

Create `shared/src/commonTest/kotlin/com/example/dalat/domain/BalancesTest.kt`:

```kotlin
package com.example.dalat.domain

import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare
import com.example.dalat.model.ExpenseType
import kotlin.test.Test
import kotlin.test.assertEquals

private fun expense(
    id: String,
    payer: String,
    type: ExpenseType,
    amount: Long,
) = Expense(
    id = id,
    tripId = "trip",
    payerMemberId = payer,
    type = type,
    category = "other",
    amount = amount,
    note = null,
    tripDay = 1,
    createdBy = "user",
    createdAt = "2026-09-16T00:00:00Z",
)

private fun share(expenseId: String, member: String, amount: Long) = ExpenseShare(
    id = "$expenseId-$member",
    expenseId = expenseId,
    tripId = "trip",
    memberId = member,
    amount = amount,
)

class BalancesTest {
    @Test
    fun groupExpenseMakesParticipantsOwePayer() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "b", "c"),
            expenses = listOf(expense("e1", "a", ExpenseType.GROUP, 300_000)),
            shares = listOf(
                share("e1", "a", 100_000),
                share("e1", "b", 100_000),
                share("e1", "c", 100_000),
            ),
        ).associateBy { it.memberId }

        assertEquals(200_000, balances.getValue("a").net)
        assertEquals(-100_000, balances.getValue("b").net)
        assertEquals(-100_000, balances.getValue("c").net)
    }

    @Test
    fun itemizedExpenseChargesEachPersonTheirOwnAmount() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "b"),
            expenses = listOf(expense("e1", "a", ExpenseType.PERSONAL_ITEMIZED, 80_000)),
            shares = listOf(share("e1", "a", 30_000), share("e1", "b", 50_000)),
        ).associateBy { it.memberId }

        assertEquals(50_000, balances.getValue("a").net)
        assertEquals(-50_000, balances.getValue("b").net)
    }

    @Test
    fun selfPaidExpenseCreatesNoDebtButCountsAsPaid() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "b"),
            expenses = listOf(expense("e1", "a", ExpenseType.PERSONAL_SELF, 25_000)),
            shares = listOf(share("e1", "a", 25_000)),
        ).associateBy { it.memberId }

        assertEquals(0, balances.getValue("a").net)
        assertEquals(25_000, balances.getValue("a").paid)
        assertEquals(0, balances.getValue("b").net)
    }

    @Test
    fun netBalancesAlwaysSumToZero() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "b", "c"),
            expenses = listOf(
                expense("e1", "a", ExpenseType.GROUP, 100_000),
                expense("e2", "b", ExpenseType.PERSONAL_ITEMIZED, 70_000),
            ),
            shares = listOf(
                share("e1", "a", 33_334), share("e1", "b", 33_333), share("e1", "c", 33_333),
                share("e2", "b", 20_000), share("e2", "c", 50_000),
            ),
        )
        assertEquals(0, balances.sumOf { it.net })
    }

    @Test
    fun memberWithNoActivityHasZeroBalance() {
        val balances = BalanceCalculator.compute(
            memberIds = listOf("a", "z"),
            expenses = listOf(expense("e1", "a", ExpenseType.PERSONAL_SELF, 10_000)),
            shares = listOf(share("e1", "a", 10_000)),
        ).associateBy { it.memberId }

        assertEquals(0, balances.getValue("z").paid)
        assertEquals(0, balances.getValue("z").owed)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :shared:wasmJsTest --tests "*BalancesTest*"`
Expected: FAIL — unresolved reference `BalanceCalculator`.

- [ ] **Step 3: Implement balances**

Create `shared/src/commonMain/kotlin/com/example/dalat/domain/Balances.kt`:

```kotlin
package com.example.dalat.domain

import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare

data class MemberBalance(
    val memberId: String,
    val paid: Long,
    val owed: Long,
) {
    val net: Long get() = paid - owed
}

object BalanceCalculator {
    fun compute(
        memberIds: List<String>,
        expenses: List<Expense>,
        shares: List<ExpenseShare>,
    ): List<MemberBalance> {
        val paid = mutableMapOf<String, Long>()
        val owed = mutableMapOf<String, Long>()

        for (expense in expenses) {
            paid[expense.payerMemberId] = (paid[expense.payerMemberId] ?: 0L) + expense.amount
        }
        for (share in shares) {
            owed[share.memberId] = (owed[share.memberId] ?: 0L) + share.amount
        }

        return memberIds.map { id ->
            MemberBalance(memberId = id, paid = paid[id] ?: 0L, owed = owed[id] ?: 0L)
        }
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :shared:wasmJsTest --tests "*BalancesTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Write the failing settlement test**

Create `shared/src/commonTest/kotlin/com/example/dalat/domain/SettlementTest.kt`:

```kotlin
package com.example.dalat.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun balance(id: String, net: Long) =
    if (net >= 0) MemberBalance(id, paid = net, owed = 0) else MemberBalance(id, paid = 0, owed = -net)

class SettlementTest {
    @Test
    fun settledGroupNeedsNoTransactions() {
        val result = SettlementCalculator.simplify(
            listOf(balance("a", 0), balance("b", 0)),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun twoDebtorsPayTheSingleCreditor() {
        val result = SettlementCalculator.simplify(
            listOf(balance("a", 200_000), balance("b", -100_000), balance("c", -100_000)),
        )
        assertEquals(2, result.size)
        assertTrue(result.all { it.toMemberId == "a" })
        assertEquals(200_000, result.sumOf { it.amount })
    }

    @Test
    fun collapsesCircularDebtIntoOneTransfer() {
        // a owes b 50k, b owes c 50k -> a should just pay c.
        val result = SettlementCalculator.simplify(
            listOf(balance("a", -50_000), balance("b", 0), balance("c", 50_000)),
        )
        assertEquals(
            listOf(SettlementTransaction("a", "c", 50_000)),
            result,
        )
    }

    @Test
    fun neverExceedsMemberCountMinusOneTransactions() {
        val balances = listOf(
            balance("a", 300_000), balance("b", 150_000),
            balance("c", -200_000), balance("d", -150_000), balance("e", -100_000),
        )
        val result = SettlementCalculator.simplify(balances)
        assertTrue(result.size <= balances.size - 1, "got ${result.size} transactions")
    }

    @Test
    fun transactionsFullyCancelEveryBalance() {
        val balances = listOf(
            balance("a", 123_456), balance("b", -23_456),
            balance("c", -100_000), balance("d", 0),
        )
        val result = SettlementCalculator.simplify(balances)
        val settled = balances.associate { it.memberId to it.net }.toMutableMap()
        for (t in result) {
            settled[t.fromMemberId] = settled.getValue(t.fromMemberId) + t.amount
            settled[t.toMemberId] = settled.getValue(t.toMemberId) - t.amount
        }
        assertTrue(settled.values.all { it == 0L }, "leftover balances: $settled")
    }

    @Test
    fun outputIsDeterministic() {
        val balances = listOf(
            balance("b", -100_000), balance("a", -100_000), balance("c", 200_000),
        )
        assertEquals(SettlementCalculator.simplify(balances), SettlementCalculator.simplify(balances))
    }
}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `./gradlew :shared:wasmJsTest --tests "*SettlementTest*"`
Expected: FAIL — unresolved reference `SettlementCalculator`.

- [ ] **Step 7: Implement settlement**

Create `shared/src/commonMain/kotlin/com/example/dalat/domain/Settlement.kt`. The sort includes a member-id tiebreak so the output is stable — `settlement_marks` rows are keyed by the `(from, to)` pair, and a reshuffled order would orphan the "đã trả" ticks:

```kotlin
package com.example.dalat.domain

data class SettlementTransaction(
    val fromMemberId: String,
    val toMemberId: String,
    val amount: Long,
)

object SettlementCalculator {
    fun simplify(balances: List<MemberBalance>): List<SettlementTransaction> {
        val order = compareByDescending<Pair<String, Long>> { it.second }.thenBy { it.first }
        val debtors = balances.filter { it.net < 0 }
            .map { it.memberId to -it.net }
            .sortedWith(order)
        val creditors = balances.filter { it.net > 0 }
            .map { it.memberId to it.net }
            .sortedWith(order)

        val transactions = mutableListOf<SettlementTransaction>()
        var debtorIndex = 0
        var creditorIndex = 0
        var debtorRemaining = debtors.firstOrNull()?.second ?: 0L
        var creditorRemaining = creditors.firstOrNull()?.second ?: 0L

        while (debtorIndex < debtors.size && creditorIndex < creditors.size) {
            val amount = minOf(debtorRemaining, creditorRemaining)
            if (amount > 0) {
                transactions += SettlementTransaction(
                    fromMemberId = debtors[debtorIndex].first,
                    toMemberId = creditors[creditorIndex].first,
                    amount = amount,
                )
            }
            debtorRemaining -= amount
            creditorRemaining -= amount
            if (debtorRemaining == 0L) {
                debtorIndex++
                debtorRemaining = debtors.getOrNull(debtorIndex)?.second ?: 0L
            }
            if (creditorRemaining == 0L) {
                creditorIndex++
                creditorRemaining = creditors.getOrNull(creditorIndex)?.second ?: 0L
            }
        }
        return transactions
    }
}
```

- [ ] **Step 8: Run it to verify it passes**

Run: `./gradlew :shared:wasmJsTest --tests "*SettlementTest*"`
Expected: PASS, 6 tests.

- [ ] **Step 9: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/domain shared/src/commonTest/kotlin/com/example/dalat/domain
git commit -m "feat: add balance computation and minimal-transaction settlement"
```

---

## Task 6: Dashboard statistics

**Files:**
- Create: `shared/src/commonMain/kotlin/com/example/dalat/domain/TripStats.kt`
- Test: `shared/src/commonTest/kotlin/com/example/dalat/domain/TripStatsTest.kt`

**Interfaces:**
- Consumes: `Expense`, `ExpenseShare`, `Category` from Task 3.
- Produces: `data class TripStats(val total: Long, val byDay: Map<Int, Long>, val byCategory: Map<Category, Long>, val spendPerMember: Map<String, Long>)` and `TripStats.Companion.compute(expenses: List<Expense>, shares: List<ExpenseShare>): TripStats`. `spendPerMember` is *consumption* (sum of that member's shares), not cash paid — that is what answers "chuyến này tôi tiêu hết bao nhiêu".

- [ ] **Step 1: Write the failing test**

Create `shared/src/commonTest/kotlin/com/example/dalat/domain/TripStatsTest.kt`:

```kotlin
package com.example.dalat.domain

import com.example.dalat.model.Category
import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare
import com.example.dalat.model.ExpenseType
import kotlin.test.Test
import kotlin.test.assertEquals

private fun statsExpense(id: String, amount: Long, day: Int, category: String) = Expense(
    id = id,
    tripId = "trip",
    payerMemberId = "a",
    type = ExpenseType.GROUP,
    category = category,
    amount = amount,
    note = null,
    tripDay = day,
    createdBy = "user",
    createdAt = "2026-09-16T00:00:00Z",
)

private fun statsShare(expenseId: String, member: String, amount: Long) = ExpenseShare(
    id = "$expenseId-$member",
    expenseId = expenseId,
    tripId = "trip",
    memberId = member,
    amount = amount,
)

class TripStatsTest {
    private val expenses = listOf(
        statsExpense("e1", 300_000, day = 1, category = "lodging"),
        statsExpense("e2", 200_000, day = 1, category = "group_meal"),
        statsExpense("e3", 100_000, day = 2, category = "lodging"),
    )
    private val shares = listOf(
        statsShare("e1", "a", 150_000), statsShare("e1", "b", 150_000),
        statsShare("e2", "a", 200_000),
        statsShare("e3", "b", 100_000),
    )

    @Test
    fun sumsEverything() {
        assertEquals(600_000, TripStats.compute(expenses, shares).total)
    }

    @Test
    fun groupsByTripDay() {
        assertEquals(mapOf(1 to 500_000L, 2 to 100_000L), TripStats.compute(expenses, shares).byDay)
    }

    @Test
    fun groupsByCategory() {
        val byCategory = TripStats.compute(expenses, shares).byCategory
        assertEquals(400_000, byCategory.getValue(Category.LODGING))
        assertEquals(200_000, byCategory.getValue(Category.GROUP_MEAL))
    }

    @Test
    fun sumsConsumptionPerMember() {
        assertEquals(
            mapOf("a" to 350_000L, "b" to 250_000L),
            TripStats.compute(expenses, shares).spendPerMember,
        )
    }

    @Test
    fun handlesEmptyTrip() {
        val stats = TripStats.compute(emptyList(), emptyList())
        assertEquals(0, stats.total)
        assertEquals(emptyMap(), stats.byDay)
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :shared:wasmJsTest --tests "*TripStatsTest*"`
Expected: FAIL — unresolved reference `TripStats`.

- [ ] **Step 3: Implement the stats**

Create `shared/src/commonMain/kotlin/com/example/dalat/domain/TripStats.kt`:

```kotlin
package com.example.dalat.domain

import com.example.dalat.model.Category
import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare

data class TripStats(
    val total: Long,
    val byDay: Map<Int, Long>,
    val byCategory: Map<Category, Long>,
    val spendPerMember: Map<String, Long>,
) {
    companion object {
        fun compute(expenses: List<Expense>, shares: List<ExpenseShare>): TripStats = TripStats(
            total = expenses.sumOf { it.amount },
            byDay = expenses.groupBy { it.tripDay }
                .mapValues { (_, list) -> list.sumOf { it.amount } }
                .toSortedMap(),
            byCategory = expenses.groupBy { Category.fromId(it.category) }
                .mapValues { (_, list) -> list.sumOf { it.amount } },
            spendPerMember = shares.groupBy { it.memberId }
                .mapValues { (_, list) -> list.sumOf { it.amount } },
        )
    }
}
```

If `toSortedMap()` is unavailable on this Kotlin/Wasm target, replace it with
`.entries.sortedBy { it.key }.associate { it.key to it.value }`.

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :shared:wasmJsTest --tests "*TripStatsTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 5: Run the whole suite**

Run: `./gradlew :shared:wasmJsTest`
Expected: PASS — Money, Split, Balances, Settlement, TripStats.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/domain/TripStats.kt shared/src/commonTest/kotlin/com/example/dalat/domain/TripStatsTest.kt
git commit -m "feat: add trip statistics aggregation"
```

---

## Task 7: Auth repository and Google sign-in

**Files:**
- Create: `shared/src/commonMain/kotlin/com/example/dalat/data/AuthRepository.kt`

**Interfaces:**
- Consumes: `supabase` from Task 2.
- Produces: `AuthRepository.sessionStatus: StateFlow<SessionStatus>`, `signInWithGoogle()`, `signOut()`, `currentUserId(): String?`, `suggestedDisplayName(): String`.

There is no unit test here — it is pure I/O against a live browser redirect. Verification is manual and specified in Steps 3–5.

- [ ] **Step 1: Implement the repository**

Create `shared/src/commonMain/kotlin/com/example/dalat/data/AuthRepository.kt`:

```kotlin
package com.example.dalat.data

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object AuthRepository {
    val sessionStatus: StateFlow<SessionStatus> get() = supabase.auth.sessionStatus

    suspend fun signInWithGoogle() {
        supabase.auth.signInWith(Google)
    }

    suspend fun signOut() {
        supabase.auth.signOut()
    }

    fun currentUserId(): String? = supabase.auth.currentUserOrNull()?.id

    fun suggestedDisplayName(): String {
        val user = supabase.auth.currentUserOrNull() ?: return ""
        val metadata = user.userMetadata
        val fromMetadata = metadata?.get("full_name")?.jsonPrimitive?.contentOrNull
            ?: metadata?.get("name")?.jsonPrimitive?.contentOrNull
        return fromMetadata ?: user.email?.substringBefore('@') ?: ""
    }
}
```

- [ ] **Step 2: Add a temporary sign-in probe**

Replace the body of `App()` in `shared/src/commonMain/kotlin/com/example/dalat/App.kt` with a throwaway probe (Task 10 replaces this entirely):

```kotlin
package com.example.dalat

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.example.dalat.data.AuthRepository
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.launch

@Composable
fun App() {
    MaterialTheme {
        val status by AuthRepository.sessionStatus.collectAsState()
        val scope = rememberCoroutineScope()
        Column {
            Text("Status: ${status::class.simpleName}")
            if (status is SessionStatus.Authenticated) {
                Text("User: ${AuthRepository.currentUserId()}")
                Text("Name: ${AuthRepository.suggestedDisplayName()}")
                Button(onClick = { scope.launch { AuthRepository.signOut() } }) { Text("Sign out") }
            } else {
                Button(onClick = { scope.launch { AuthRepository.signInWithGoogle() } }) {
                    Text("Sign in with Google")
                }
            }
        }
    }
}
```

Delete the now-unused imports of `Greeting`/`Res` that the scaffold left behind if the compiler complains.

- [ ] **Step 3: Verify the round trip in a browser**

Run: `./gradlew :webApp:wasmJsBrowserDevelopmentRun` and open `http://localhost:8080`.

1. Click "Sign in with Google" → expect a redirect to Google's consent screen.
2. Complete it → expect to land back on `localhost:8080` with `Status: Authenticated` and a real user id and name.

If the redirect fails with `redirect_uri_mismatch`, the Google OAuth client's authorized redirect URI does not match `<SUPABASE_URL>/auth/v1/callback` (Task 1 Step 7). If it lands back but stays `NotAuthenticated`, the Supabase *Redirect URLs* allow-list is missing `http://localhost:8080/**`.

- [ ] **Step 4: Verify session persistence across reload**

Reload the page. Expected: `Status: Authenticated` without signing in again.

**If it reverts to `NotAuthenticated`**, supabase-kt is not persisting the session on wasmJs. Fix it by installing a localStorage-backed session manager. Add to `SupabaseClient.kt`:

```kotlin
private fun storageGet(key: String): String? = js("window.localStorage.getItem(key)")
private fun storageSet(key: String, value: String) { js("window.localStorage.setItem(key, value)") }
private fun storageRemove(key: String) { js("window.localStorage.removeItem(key)") }

private class LocalStorageSessionManager : SessionManager {
    private val key = "dalat.session"
    override suspend fun saveSession(session: UserSession) =
        storageSet(key, Json.encodeToString(session))
    override suspend fun loadSession(): UserSession? =
        storageGet(key)?.let { Json.decodeFromString<UserSession>(it) }
    override suspend fun deleteSession() = storageRemove(key)
}
```

and reference it in the `install(Auth)` block with `sessionManager = LocalStorageSessionManager()`. Imports: `io.github.jan.supabase.auth.SessionManager`, `io.github.jan.supabase.auth.user.UserSession`. Re-run Steps 3–4.

- [ ] **Step 5: Verify a row actually lands in `auth.users`**

Using the Supabase MCP `execute_sql`:

```sql
select id, email, raw_user_meta_data->>'full_name' as name from auth.users;
```

Expected: one row matching the Google account just used.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat
git commit -m "feat: add Google sign-in through Supabase Auth"
```

---

## Task 8: Trip repository

**Files:**
- Create: `shared/src/commonMain/kotlin/com/example/dalat/data/TripRepository.kt`

**Interfaces:**
- Consumes: `supabase` (Task 2), `Trip`/`TripMember` (Task 3).
- Produces: `TripRepository.listTrips()`, `createTrip(name, startDate, dayCount, displayName): String`, `joinTrip(tripCode, displayName): String`, `getTrip(tripId): Trip`, `listMembers(tripId): List<TripMember>`. `startDate` is an ISO date string (`"2026-12-20"`); the returned `String` from create/join is the trip id.

- [ ] **Step 1: Implement the repository**

Create `shared/src/commonMain/kotlin/com/example/dalat/data/TripRepository.kt`:

```kotlin
package com.example.dalat.data

import com.example.dalat.model.Trip
import com.example.dalat.model.TripMember
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
private data class CreateTripParams(
    @SerialName("p_name") val name: String,
    @SerialName("p_start_date") val startDate: String,
    @SerialName("p_day_count") val dayCount: Int,
    @SerialName("p_display_name") val displayName: String,
)

@Serializable
private data class JoinTripParams(
    @SerialName("p_trip_code") val tripCode: String,
    @SerialName("p_display_name") val displayName: String,
)

object TripRepository {
    suspend fun listTrips(): List<Trip> =
        supabase.from("trips").select().decodeList<Trip>()

    suspend fun getTrip(tripId: String): Trip =
        supabase.from("trips").select { filter { eq("id", tripId) } }.decodeSingle<Trip>()

    suspend fun listMembers(tripId: String): List<TripMember> =
        supabase.from("trip_members").select { filter { eq("trip_id", tripId) } }
            .decodeList<TripMember>()

    // Both writes go through SECURITY DEFINER RPCs — see the spec's RLS section
    // for why direct inserts are not possible here.
    suspend fun createTrip(
        name: String,
        startDate: String,
        dayCount: Int,
        displayName: String,
    ): String = supabase.postgrest
        .rpc("create_trip", CreateTripParams(name, startDate, dayCount, displayName))
        .decodeAs<String>()

    suspend fun joinTrip(tripCode: String, displayName: String): String = supabase.postgrest
        .rpc("join_trip", JoinTripParams(tripCode, displayName))
        .decodeAs<String>()
}
```

- [ ] **Step 2: Wire a temporary probe into `App()`**

Inside the `SessionStatus.Authenticated` branch of the probe `App()`, add:

```kotlin
var log by remember { mutableStateOf("") }
Button(onClick = {
    scope.launch {
        log = runCatching {
            val id = TripRepository.createTrip("Đà Lạt test", "2026-12-20", 5, "Test User")
            val trip = TripRepository.getTrip(id)
            "created ${trip.tripCode}, members=${TripRepository.listMembers(id).size}"
        }.getOrElse { "ERROR: ${it.message}" }
    }
}) { Text("Probe create trip") }
Text(log)
```

- [ ] **Step 3: Verify create, read-back and RLS**

Run `./gradlew :webApp:wasmJsBrowserDevelopmentRun`, sign in, click "Probe create trip".
Expected: `created XXXXXX, members=1` — a six-character code and exactly one member.

Then verify the join path and that RLS actually isolates trips, via MCP `execute_sql`:

```sql
select t.trip_code, count(m.id) as members
from trips t left join trip_members m on m.trip_id = t.id
group by t.trip_code;
```

Expected: one row, `members` = 1.

- [ ] **Step 4: Verify the RLS isolation boundary**

Using MCP `execute_sql`, confirm an unrelated user sees nothing:

```sql
select public.is_trip_member(id) from trips;
```

Expected: `false` for every row — the MCP connection is not an authenticated app user, which proves the policy is not accidentally permissive. If this returns `true` or errors, stop and fix the policy before continuing.

- [ ] **Step 5: Remove the probe button and commit**

Delete the probe button and `log` state from `App()` (keep the sign-in probe for now).

```bash
git add shared/src/commonMain/kotlin/com/example/dalat
git commit -m "feat: add trip repository with create/join RPCs"
```

---

## Task 9: Expense and settlement repository

**Files:**
- Create: `shared/src/commonMain/kotlin/com/example/dalat/data/ExpenseRepository.kt`

**Interfaces:**
- Consumes: `supabase` (Task 2); `Expense`, `ExpenseShare`, `NewExpense`, `NewExpenseShare`, `SettlementMark` (Task 3).
- Produces: `ExpenseRepository.listExpenses(tripId)`, `listShares(tripId)`, `listSettlementMarks(tripId)`, `addExpense(expense: NewExpense, shares: Map<String, Long>)`, `deleteExpense(expenseId)`, `setSettlementMark(tripId, fromMemberId, toMemberId, isPaid)`, and `realtimeChannel(tripId): RealtimeChannel` + `changeFlows(channel, tripId): List<Flow<PostgresAction>>`.

- [ ] **Step 1: Implement the repository**

Create `shared/src/commonMain/kotlin/com/example/dalat/data/ExpenseRepository.kt`:

```kotlin
package com.example.dalat.data

import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare
import com.example.dalat.model.NewExpense
import com.example.dalat.model.NewExpenseShare
import com.example.dalat.model.SettlementMark
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.flow.Flow

object ExpenseRepository {
    private val realtimeTables = listOf("expenses", "expense_shares", "settlement_marks")

    suspend fun listExpenses(tripId: String): List<Expense> =
        supabase.from("expenses").select {
            filter { eq("trip_id", tripId) }
            order("created_at", Order.DESCENDING)
        }.decodeList<Expense>()

    suspend fun listShares(tripId: String): List<ExpenseShare> =
        supabase.from("expense_shares").select { filter { eq("trip_id", tripId) } }
            .decodeList<ExpenseShare>()

    suspend fun listSettlementMarks(tripId: String): List<SettlementMark> =
        supabase.from("settlement_marks").select { filter { eq("trip_id", tripId) } }
            .decodeList<SettlementMark>()

    // The expense row must exist before its shares, because expense_shares'
    // INSERT policy checks that the caller owns the parent expense.
    suspend fun addExpense(expense: NewExpense, shares: Map<String, Long>) {
        require(shares.isNotEmpty()) { "Chi tiêu phải có ít nhất một người" }
        require(shares.values.sum() == expense.amount) {
            "Tổng phần chia (${shares.values.sum()}) phải bằng số tiền (${expense.amount})"
        }
        val inserted = supabase.from("expenses").insert(expense) { select() }.decodeSingle<Expense>()
        val rows = shares.map { (memberId, amount) ->
            NewExpenseShare(
                expenseId = inserted.id,
                tripId = inserted.tripId,
                memberId = memberId,
                amount = amount,
            )
        }
        supabase.from("expense_shares").insert(rows)
    }

    // Shares are removed by the ON DELETE CASCADE on expense_shares.expense_id.
    suspend fun deleteExpense(expenseId: String) {
        supabase.from("expenses").delete { filter { eq("id", expenseId) } }
    }

    suspend fun setSettlementMark(
        tripId: String,
        fromMemberId: String,
        toMemberId: String,
        isPaid: Boolean,
    ) {
        supabase.from("settlement_marks").upsert(
            SettlementMark(tripId, fromMemberId, toMemberId, isPaid),
        ) {
            onConflict = "trip_id,from_member_id,to_member_id"
        }
    }

    fun realtimeChannel(tripId: String): RealtimeChannel = supabase.channel("trip-$tripId")

    // Must be called BEFORE channel.subscribe() — supabase-kt registers the
    // postgres_changes bindings when the flows are created, and a subscribed
    // channel will not pick up new bindings.
    fun changeFlows(channel: RealtimeChannel, tripId: String): List<Flow<PostgresAction>> =
        realtimeTables.map { tableName ->
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = tableName
                filter("trip_id", FilterOperator.EQ, tripId)
            }
        }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :shared:compileKotlinWasmJs`
Expected: `BUILD SUCCESSFUL`. If `onConflict` or `Order` do not resolve, check the supabase-kt 3.8.0 API surface rather than guessing at names.

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/data/ExpenseRepository.kt
git commit -m "feat: add expense, share and settlement-mark repository"
```

---

## Task 10: Theme, app shell and sign-in screen

This task deletes the scaffold's demo code and establishes the real navigation skeleton.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/example/dalat/ui/Theme.kt`
- Create: `shared/src/commonMain/kotlin/com/example/dalat/ui/components/Common.kt`
- Create: `shared/src/commonMain/kotlin/com/example/dalat/ui/SignInScreen.kt`
- Modify: `shared/src/commonMain/kotlin/com/example/dalat/App.kt`
- Modify: `webApp/src/webMain/resources/index.html`
- Delete: `shared/src/commonMain/kotlin/com/example/dalat/Greeting.kt`, `GreetingUtil.kt`, `Platform.kt`, `shared/src/jsMain/kotlin/com/example/dalat/Platform.js.kt`, `shared/src/wasmJsMain/kotlin/com/example/dalat/Platform.wasmJs.kt`, `shared/src/commonTest/kotlin/com/example/dalat/SharedCommonTest.kt`, `shared/src/webTest/kotlin/com/example/dalat/SharedLogicWebTest.kt`

**Interfaces:**
- Produces: `DalatTheme(content: @Composable () -> Unit)`, `MoneyText(amount: Long, ...)`, `SectionCard(title: String, content: @Composable ColumnScope.() -> Unit)`, `AmountField(value: String, onValueChange: (String) -> Unit, label: String)`, `SignInScreen()`. Tasks 11–15 use all of these.

- [ ] **Step 1: Write the theme**

Create `shared/src/commonMain/kotlin/com/example/dalat/ui/Theme.kt`:

```kotlin
package com.example.dalat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DalatColors = lightColorScheme(
    primary = Color(0xFF2E7D5B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8E6CE),
    onPrimaryContainer = Color(0xFF00210F),
    secondary = Color(0xFF7A5C2E),
    surface = Color(0xFFFCFDF8),
    background = Color(0xFFF4F7F2),
    error = Color(0xFFBA1A1A),
)

@Composable
fun DalatTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DalatColors, content = content)
}
```

- [ ] **Step 2: Write the shared components**

Create `shared/src/commonMain/kotlin/com/example/dalat/ui/components/Common.kt`:

```kotlin
package com.example.dalat.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.dalat.domain.formatVnd

@Composable
fun MoneyText(
    amount: Long,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    bold: Boolean = false,
) {
    Text(
        text = formatVnd(amount),
        modifier = modifier,
        color = color,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
fun SectionCard(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

// Digits only: VND has no sub-unit, and a text field that silently accepts
// "12.5" would round-trip into the wrong amount.
@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input -> onValueChange(input.filter { it.isDigit() }.take(12)) },
        label = { Text(label) },
        supportingText = { Text(formatVnd(value.toLongOrNull() ?: 0L)) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}
```

- [ ] **Step 3: Write the sign-in screen**

Create `shared/src/commonMain/kotlin/com/example/dalat/ui/SignInScreen.kt`:

```kotlin
package com.example.dalat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.dalat.data.AuthRepository
import kotlinx.coroutines.launch

@Composable
fun SignInScreen() {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Chia tiền Đà Lạt", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Ghi chi tiêu cả nhóm, cuối chuyến biết ai trả ai bao nhiêu.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = { scope.launch { AuthRepository.signInWithGoogle() } }) {
            Text("Đăng nhập với Google")
        }
    }
}
```

- [ ] **Step 4: Replace `App()` with the real shell**

Overwrite `shared/src/commonMain/kotlin/com/example/dalat/App.kt`:

```kotlin
package com.example.dalat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.dalat.data.AuthRepository
import com.example.dalat.ui.DalatTheme
import com.example.dalat.ui.SignInScreen
import com.example.dalat.ui.TripHomeScreen
import com.example.dalat.ui.TripPickerScreen
import io.github.jan.supabase.auth.status.SessionStatus

@Composable
fun App() {
    DalatTheme {
        Surface(Modifier.fillMaxSize().safeContentPadding()) {
            val status by AuthRepository.sessionStatus.collectAsState()
            when (status) {
                is SessionStatus.Authenticated -> SignedInApp()
                is SessionStatus.NotAuthenticated -> SignInScreen()
                else -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            }
        }
    }
}

@Composable
private fun SignedInApp() {
    var openTripId by remember { mutableStateOf<String?>(null) }
    val tripId = openTripId
    if (tripId == null) {
        TripPickerScreen(onTripOpened = { openTripId = it })
    } else {
        TripHomeScreen(tripId = tripId, onLeaveTrip = { openTripId = null })
    }
}
```

`TripPickerScreen` and `TripHomeScreen` do not exist yet, so this will not compile until Tasks 11–12. Create temporary one-line stubs for both now so each task stays independently buildable:

```kotlin
// shared/src/commonMain/kotlin/com/example/dalat/ui/TripPickerScreen.kt
package com.example.dalat.ui
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
@Composable fun TripPickerScreen(onTripOpened: (String) -> Unit) { Text("TODO Task 11") }
```

```kotlin
// shared/src/commonMain/kotlin/com/example/dalat/ui/TripHomeScreen.kt
package com.example.dalat.ui
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
@Composable fun TripHomeScreen(tripId: String, onLeaveTrip: () -> Unit) { Text("TODO Task 12: $tripId") }
```

- [ ] **Step 5: Delete the scaffold demo files**

```bash
git rm shared/src/commonMain/kotlin/com/example/dalat/Greeting.kt \
       shared/src/commonMain/kotlin/com/example/dalat/GreetingUtil.kt \
       shared/src/commonMain/kotlin/com/example/dalat/Platform.kt \
       shared/src/jsMain/kotlin/com/example/dalat/Platform.js.kt \
       shared/src/wasmJsMain/kotlin/com/example/dalat/Platform.wasmJs.kt \
       shared/src/commonTest/kotlin/com/example/dalat/SharedCommonTest.kt \
       shared/src/webTest/kotlin/com/example/dalat/SharedLogicWebTest.kt
```

- [ ] **Step 6: Update the page shell**

In `webApp/src/webMain/resources/index.html`: change `<html lang="en">` to `<html lang="vi">`, change the `<title>` to `Chia tiền Đà Lạt`, and change the viewport meta to
`<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">`.
Leave the `<script src="webApp.js">` tag and the loading spinner alone.

- [ ] **Step 7: Verify**

Run: `./gradlew :shared:wasmJsTest && ./gradlew :webApp:wasmJsBrowserDevelopmentRun`
Expected: tests still pass; signed out shows the Vietnamese sign-in screen; signed in shows `TODO Task 11`. Narrow the browser window to ~390px — nothing should overflow horizontally.

- [ ] **Step 8: Commit**

```bash
git add -A shared webApp
git commit -m "feat: add app theme, shell and sign-in screen"
```

---

## Task 11: Trip picker — create and join

**Files:**
- Modify: `shared/src/commonMain/kotlin/com/example/dalat/ui/TripPickerScreen.kt`

**Interfaces:**
- Consumes: `TripRepository` (Task 8), `AuthRepository.suggestedDisplayName()` (Task 7), `SectionCard` (Task 10).
- Produces: `TripPickerScreen(onTripOpened: (String) -> Unit)`.

- [ ] **Step 1: Implement the screen**

Overwrite `shared/src/commonMain/kotlin/com/example/dalat/ui/TripPickerScreen.kt`:

```kotlin
package com.example.dalat.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dalat.data.AuthRepository
import com.example.dalat.data.TripRepository
import com.example.dalat.model.Trip
import com.example.dalat.ui.components.SectionCard
import kotlinx.coroutines.launch

@Composable
fun TripPickerScreen(onTripOpened: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var trips by remember { mutableStateOf<List<Trip>>(emptyList()) }
    var displayName by remember { mutableStateOf(AuthRepository.suggestedDisplayName()) }
    var tripName by remember { mutableStateOf("Đà Lạt") }
    var startDate by remember { mutableStateOf("") }
    var joinCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        error = runCatching { trips = TripRepository.listTrips() }.exceptionOrNull()?.message
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Chuyến đi của bạn", style = MaterialTheme.typography.headlineSmall)

        if (trips.isEmpty()) {
            Text("Chưa có chuyến nào.", Modifier.padding(vertical = 8.dp))
        } else {
            SectionCard("Mở chuyến có sẵn") {
                trips.forEach { trip ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onTripOpened(trip.id) }.padding(vertical = 12.dp),
                    ) {
                        Text(trip.name, style = MaterialTheme.typography.titleSmall)
                        Text("Mã: ${trip.tripCode} · ${trip.dayCount} ngày từ ${trip.startDate}")
                    }
                    HorizontalDivider()
                }
            }
        }

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Tên hiển thị của bạn") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        SectionCard("Tạo chuyến mới") {
            OutlinedTextField(
                value = tripName,
                onValueChange = { tripName = it },
                label = { Text("Tên chuyến") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = startDate,
                onValueChange = { startDate = it },
                label = { Text("Ngày bắt đầu (YYYY-MM-DD)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                enabled = !busy && displayName.isNotBlank() && startDate.length == 10,
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { TripRepository.createTrip(tripName, startDate, 5, displayName) }
                            .onSuccess { onTripOpened(it) }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Tạo chuyến") }
        }

        SectionCard("Vào chuyến bằng mã") {
            OutlinedTextField(
                value = joinCode,
                onValueChange = { joinCode = it.uppercase().take(6) },
                label = { Text("Mã chuyến (6 ký tự)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                enabled = !busy && joinCode.length == 6 && displayName.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = true
                        runCatching { TripRepository.joinTrip(joinCode, displayName) }
                            .onSuccess { onTripOpened(it) }
                            .onFailure { error = it.message }
                        busy = false
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Vào chuyến") }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

        TextButton(onClick = { scope.launch { AuthRepository.signOut() } }) { Text("Đăng xuất") }
    }
}
```

- [ ] **Step 2: Verify create and list**

Run `./gradlew :webApp:wasmJsBrowserDevelopmentRun`, sign in, fill in a start date like `2026-12-20`, click "Tạo chuyến".
Expected: the app switches to `TODO Task 12: <uuid>`. Reload, and the new trip appears under "Mở chuyến có sẵn" with a 6-character code.

- [ ] **Step 3: Verify the join path with a second account**

Open a private browser window, sign in with a **different** Google account, enter the 6-character code, click "Vào chuyến".
Expected: it opens the same trip. Confirm with MCP `execute_sql`:

```sql
select t.trip_code, count(*) as members
from trips t join trip_members m on m.trip_id = t.id
group by t.trip_code;
```

Expected: `members` = 2. A wrong code must surface `TRIP_NOT_FOUND` in the error text rather than silently doing nothing.

- [ ] **Step 4: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/ui/TripPickerScreen.kt
git commit -m "feat: add trip picker with create and join-by-code"
```

---

## Task 12: Trip state, home scaffold and expense feed

**Files:**
- Create: `shared/src/commonMain/kotlin/com/example/dalat/ui/TripViewModel.kt`
- Modify: `shared/src/commonMain/kotlin/com/example/dalat/ui/TripHomeScreen.kt`
- Create: `shared/src/commonMain/kotlin/com/example/dalat/ui/FeedScreen.kt`

**Interfaces:**
- Consumes: every repository and every domain calculator.
- Produces: `TripUiState` (with `balances`, `settlements`, `stats`, `memberName(id)`, `sharesOf(expenseId)`), `TripViewModel(tripId)` exposing `state: StateFlow<TripUiState>`, `refresh()`, `addExpense(...)`, `deleteExpense(id)`, `setSettlementMark(...)`, plus `TripHomeScreen(tripId, onLeaveTrip)` and `FeedScreen(state, onDelete)`. Tasks 13–15 render from `TripUiState` and call `TripViewModel` methods.

One ViewModel owns everything for a trip so the three tabs never disagree and only one Realtime channel exists.

- [ ] **Step 1: Write the ViewModel**

Create `shared/src/commonMain/kotlin/com/example/dalat/ui/TripViewModel.kt`:

```kotlin
package com.example.dalat.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.dalat.data.AuthRepository
import com.example.dalat.data.ExpenseRepository
import com.example.dalat.data.TripRepository
import com.example.dalat.domain.BalanceCalculator
import com.example.dalat.domain.MemberBalance
import com.example.dalat.domain.SettlementCalculator
import com.example.dalat.domain.SettlementTransaction
import com.example.dalat.domain.TripStats
import com.example.dalat.model.Expense
import com.example.dalat.model.ExpenseShare
import com.example.dalat.model.NewExpense
import com.example.dalat.model.SettlementMark
import com.example.dalat.model.Trip
import com.example.dalat.model.TripMember
import io.github.jan.supabase.realtime.RealtimeChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class TripUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val trip: Trip? = null,
    val members: List<TripMember> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val shares: List<ExpenseShare> = emptyList(),
    val marks: List<SettlementMark> = emptyList(),
    val currentMemberId: String? = null,
) {
    // Recomputed on read. At five members and a few hundred expenses this is
    // free, and it removes any chance of the tabs showing stale derived data.
    val balances: List<MemberBalance>
        get() = BalanceCalculator.compute(members.map { it.id }, expenses, shares)

    val settlements: List<SettlementTransaction>
        get() = SettlementCalculator.simplify(balances)

    val stats: TripStats get() = TripStats.compute(expenses, shares)

    fun memberName(memberId: String): String =
        members.firstOrNull { it.id == memberId }?.displayName ?: "?"

    fun sharesOf(expenseId: String): List<ExpenseShare> = shares.filter { it.expenseId == expenseId }

    fun isPaid(from: String, to: String): Boolean =
        marks.any { it.fromMemberId == from && it.toMemberId == to && it.isPaid }
}

class TripViewModel(private val tripId: String) : ViewModel() {
    private val _state = MutableStateFlow(TripUiState())
    val state: StateFlow<TripUiState> = _state.asStateFlow()

    private var channel: RealtimeChannel? = null

    init {
        refresh()
        startRealtime()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching {
                val trip = TripRepository.getTrip(tripId)
                val members = TripRepository.listMembers(tripId)
                val userId = AuthRepository.currentUserId()
                TripUiState(
                    loading = false,
                    trip = trip,
                    members = members,
                    expenses = ExpenseRepository.listExpenses(tripId),
                    shares = ExpenseRepository.listShares(tripId),
                    marks = ExpenseRepository.listSettlementMarks(tripId),
                    currentMemberId = members.firstOrNull { it.userId == userId }?.id,
                )
            }.onSuccess { _state.value = it }
                .onFailure { _state.value = _state.value.copy(loading = false, error = it.message) }
        }
    }

    // Any change to the trip just triggers a full reload. Merging individual
    // Realtime payloads would be faster and much easier to get subtly wrong.
    private fun startRealtime() {
        viewModelScope.launch {
            val newChannel = ExpenseRepository.realtimeChannel(tripId)
            ExpenseRepository.changeFlows(newChannel, tripId).forEach { flow ->
                launch { flow.collect { refresh() } }
            }
            newChannel.subscribe()
            channel = newChannel
        }
    }

    fun addExpense(expense: NewExpense, shares: Map<String, Long>, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            runCatching { ExpenseRepository.addExpense(expense, shares) }
                .onSuccess { refresh(); onDone(null) }
                .onFailure { onDone(it.message ?: "Lưu thất bại") }
        }
    }

    fun deleteExpense(expenseId: String) {
        viewModelScope.launch {
            runCatching { ExpenseRepository.deleteExpense(expenseId) }.onSuccess { refresh() }
        }
    }

    fun setSettlementMark(fromMemberId: String, toMemberId: String, isPaid: Boolean) {
        viewModelScope.launch {
            runCatching { ExpenseRepository.setSettlementMark(tripId, fromMemberId, toMemberId, isPaid) }
                .onSuccess { refresh() }
        }
    }

    override fun onCleared() {
        val openChannel = channel
        channel = null
        if (openChannel != null) {
            kotlinx.coroutines.MainScope().launch { openChannel.unsubscribe() }
        }
    }
}
```

- [ ] **Step 2: Write the feed**

Create `shared/src/commonMain/kotlin/com/example/dalat/ui/FeedScreen.kt`:

```kotlin
package com.example.dalat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dalat.data.AuthRepository
import com.example.dalat.model.Category
import com.example.dalat.model.ExpenseType
import com.example.dalat.ui.components.MoneyText

private fun typeLabel(type: ExpenseType): String = when (type) {
    ExpenseType.GROUP -> "Chia đều"
    ExpenseType.PERSONAL_SELF -> "Cá nhân tự trả"
    ExpenseType.PERSONAL_ITEMIZED -> "Trả hộ theo món"
}

@Composable
fun FeedScreen(state: TripUiState, onDelete: (String) -> Unit) {
    val currentUserId = AuthRepository.currentUserId()
    var dayFilter by remember { mutableStateOf<Int?>(null) }

    if (state.expenses.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Text("Chưa có chi tiêu nào. Bấm + để thêm khoản đầu tiên.")
        }
        return
    }

    val visible = state.expenses.filter { dayFilter == null || it.tripDay == dayFilter }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        ) {
            FilterChip(
                selected = dayFilter == null,
                onClick = { dayFilter = null },
                label = { Text("Tất cả") },
                modifier = Modifier.padding(end = 6.dp),
            )
            (1..(state.trip?.dayCount ?: 5)).forEach { day ->
                FilterChip(
                    selected = dayFilter == day,
                    onClick = { dayFilter = day },
                    label = { Text("Ngày $day") },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        items(visible, key = { it.id }) { expense ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            Category.fromId(expense.category).label,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        MoneyText(expense.amount, bold = true)
                    }
                    Text(
                        "${typeLabel(expense.type)} · Ngày ${expense.tripDay} · ${state.memberName(expense.payerMemberId)} trả",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    expense.note?.takeIf { it.isNotBlank() }?.let { Text(it) }
                    state.sharesOf(expense.id)
                        .filter { it.amount > 0 }
                        .forEach { share ->
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(state.memberName(share.memberId), style = MaterialTheme.typography.bodySmall)
                                MoneyText(share.amount)
                            }
                        }
                    if (expense.createdBy == currentUserId) {
                        TextButton(onClick = { onDelete(expense.id) }) { Text("Xoá") }
                    }
                }
            }
        }
    }
    }
}
```

Reindent the `LazyColumn` block one level so it sits inside the outer `Column` — the
brace layout above is written for clarity of the diff, not as final formatting.

- [ ] **Step 3: Write the home scaffold**

Overwrite `shared/src/commonMain/kotlin/com/example/dalat/ui/TripHomeScreen.kt`:

```kotlin
package com.example.dalat.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tab(val label: String) { FEED("Chi tiêu"), DASHBOARD("Thống kê"), SETTLE("Quyết toán") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripHomeScreen(tripId: String, onLeaveTrip: () -> Unit) {
    val viewModel: TripViewModel = viewModel(key = tripId) { TripViewModel(tripId) }
    val state by viewModel.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.FEED) }
    var adding by remember { mutableStateOf(false) }

    if (adding) {
        AddExpenseScreen(
            state = state,
            onDismiss = { adding = false },
            onSubmit = { expense, shares, onResult ->
                viewModel.addExpense(expense, shares) { error ->
                    if (error == null) adding = false
                    onResult(error)
                }
            },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.trip?.name ?: "Đang tải…") },
                actions = {
                    state.trip?.let { Text("Mã ${it.tripCode}", Modifier.padding(end = 8.dp)) }
                    TextButton(onClick = onLeaveTrip) { Text("Đổi chuyến") }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { entry ->
                    NavigationBarItem(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        icon = {},
                        label = { Text(entry.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == Tab.FEED) {
                FloatingActionButton(onClick = { adding = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Thêm chi tiêu")
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
                state.error != null -> Text("Lỗi: ${state.error}", Modifier.padding(16.dp))
                tab == Tab.FEED -> FeedScreen(state, onDelete = viewModel::deleteExpense)
                tab == Tab.DASHBOARD -> DashboardScreen(state)
                else -> SettleUpScreen(state, onMarkPaid = viewModel::setSettlementMark)
            }
        }
    }
}
```

`androidx.compose.ui.unit.dp` must also be imported. `AddExpenseScreen`, `DashboardScreen` and `SettleUpScreen` arrive in Tasks 13–15; add three temporary stubs matching those signatures so this task compiles:

```kotlin
// temporary, replaced in Tasks 13-15
@Composable fun AddExpenseScreen(
    state: TripUiState,
    onDismiss: () -> Unit,
    onSubmit: (com.example.dalat.model.NewExpense, Map<String, Long>, (String?) -> Unit) -> Unit,
) { Text("TODO Task 13") }
@Composable fun DashboardScreen(state: TripUiState) { Text("TODO Task 14") }
@Composable fun SettleUpScreen(state: TripUiState, onMarkPaid: (String, String, Boolean) -> Unit) { Text("TODO Task 15") }
```

If `Icons.Filled.Add` does not resolve, add `implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")` — or simply use `Text("+")` inside the FAB instead and skip the dependency.

- [ ] **Step 4: Verify the shell and Realtime plumbing**

Run `./gradlew :webApp:wasmJsBrowserDevelopmentRun`, sign in, open the trip.
Expected: top bar shows the trip name and code, three bottom tabs switch between "Chưa có chi tiêu nào", `TODO Task 14` and `TODO Task 15`. Browser console shows a Realtime WebSocket connection to `wss://<project>.supabase.co/realtime/v1/...` with no repeated reconnect errors. Once Step 5 inserts a row, the day-filter chips must appear and selecting "Ngày 2" must hide a day-1 expense.

- [ ] **Step 5: Insert a row by hand and watch it appear**

With the browser open on the Feed tab, insert an expense through MCP `execute_sql` (substitute real ids from `select id, trip_id from trip_members`):

```sql
insert into expenses (trip_id, payer_member_id, type, category, amount, trip_day, created_by)
select m.trip_id, m.id, 'group', 'group_meal', 300000, 1, m.user_id
from trip_members m limit 1
returning id;

insert into expense_shares (expense_id, trip_id, member_id, amount)
select e.id, e.trip_id, m.id, 300000 / (select count(*) from trip_members where trip_id = e.trip_id)
from expenses e join trip_members m on m.trip_id = e.trip_id
where e.id = '<the id returned above>';
```

Expected: the feed updates **without a page reload**. If it does not, the Realtime publication or the `trip_id` filter is wrong — fix it before moving on, because Tasks 13–15 all assume live refresh.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/ui
git commit -m "feat: add trip view model, home scaffold and realtime expense feed"
```

---

## Task 13: Add expense screen

The screen where the three-way expense model becomes real. For `PERSONAL_ITEMIZED` the total is **derived** from the per-person amounts rather than typed, so the shares can never disagree with the amount.

**Files:**
- Modify/Create: `shared/src/commonMain/kotlin/com/example/dalat/ui/AddExpenseScreen.kt` (replacing the Task 12 stub)

**Interfaces:**
- Consumes: `TripUiState` (Task 12), `SplitCalculator` (Task 4), `Category`/`ExpenseType`/`NewExpense` (Task 3), `AmountField` (Task 10).
- Produces: `AddExpenseScreen(state: TripUiState, onDismiss: () -> Unit, onSubmit: (NewExpense, Map<String, Long>, (String?) -> Unit) -> Unit)`.

- [ ] **Step 1: Implement the screen**

Create `shared/src/commonMain/kotlin/com/example/dalat/ui/AddExpenseScreen.kt` (delete the stub from `TripHomeScreen.kt`):

```kotlin
package com.example.dalat.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dalat.domain.SplitCalculator
import com.example.dalat.domain.formatVnd
import com.example.dalat.model.Category
import com.example.dalat.model.ExpenseType
import com.example.dalat.model.NewExpense
import com.example.dalat.ui.components.AmountField
import com.example.dalat.ui.components.SectionCard

@Composable
fun AddExpenseScreen(
    state: TripUiState,
    onDismiss: () -> Unit,
    onSubmit: (NewExpense, Map<String, Long>, (String?) -> Unit) -> Unit,
) {
    val trip = state.trip ?: return
    var type by remember { mutableStateOf(ExpenseType.GROUP) }
    var category by remember { mutableStateOf(Category.GROUP_MEAL) }
    var amountText by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var day by remember { mutableStateOf(1) }
    var payerId by remember { mutableStateOf(state.currentMemberId ?: state.members.firstOrNull()?.id ?: "") }
    var participants by remember { mutableStateOf(state.members.map { it.id }.toSet()) }
    var itemAmounts by remember { mutableStateOf(state.members.associate { it.id to "" }) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val itemTotal = itemAmounts.values.sumOf { it.toLongOrNull() ?: 0L }
    val amount = if (type == ExpenseType.PERSONAL_ITEMIZED) itemTotal else (amountText.toLongOrNull() ?: 0L)

    fun buildShares(): Map<String, Long> = when (type) {
        ExpenseType.GROUP -> SplitCalculator.splitEqually(amount, participants.toList().sorted())
        ExpenseType.PERSONAL_SELF -> mapOf(payerId to amount)
        ExpenseType.PERSONAL_ITEMIZED -> itemAmounts
            .mapValues { (_, text) -> text.toLongOrNull() ?: 0L }
            .filterValues { it > 0 }
    }

    val canSubmit = amount > 0 && payerId.isNotBlank() && !busy &&
        (type != ExpenseType.GROUP || participants.isNotEmpty()) &&
        (type != ExpenseType.PERSONAL_ITEMIZED || itemTotal > 0)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("Thêm chi tiêu", style = MaterialTheme.typography.headlineSmall)

        SectionCard("Loại chi tiêu") {
            ExpenseType.entries.forEach { entry ->
                FilterChip(
                    selected = type == entry,
                    onClick = { type = entry },
                    label = {
                        Text(
                            when (entry) {
                                ExpenseType.GROUP -> "Nhóm — chia đều"
                                ExpenseType.PERSONAL_SELF -> "Cá nhân — tự trả"
                                ExpenseType.PERSONAL_ITEMIZED -> "Trả hộ — mỗi người một giá"
                            },
                        )
                    },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        SectionCard("Người trả") {
            state.members.forEach { member ->
                FilterChip(
                    selected = payerId == member.id,
                    onClick = { payerId = member.id },
                    label = { Text(member.displayName) },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        if (type == ExpenseType.PERSONAL_ITEMIZED) {
            SectionCard("Mỗi người bao nhiêu") {
                state.members.forEach { member ->
                    AmountField(
                        value = itemAmounts[member.id].orEmpty(),
                        onValueChange = { itemAmounts = itemAmounts + (member.id to it) },
                        label = member.displayName,
                    )
                }
                Text("Tổng hoá đơn: ${formatVnd(itemTotal)}", Modifier.padding(top = 8.dp))
            }
        } else {
            SectionCard("Số tiền") {
                AmountField(value = amountText, onValueChange = { amountText = it }, label = "Số tiền (VND)")
            }
        }

        if (type == ExpenseType.GROUP) {
            SectionCard("Chia cho ai") {
                state.members.forEach { member ->
                    FilterChip(
                        selected = member.id in participants,
                        onClick = {
                            participants = if (member.id in participants) participants - member.id
                            else participants + member.id
                        },
                        label = { Text(member.displayName) },
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
                if (participants.isNotEmpty() && amount > 0) {
                    Text(
                        "Mỗi người khoảng ${formatVnd(amount / participants.size)}",
                        Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        SectionCard("Hạng mục") {
            Category.entries.forEach { entry ->
                FilterChip(
                    selected = category == entry,
                    onClick = { category = entry },
                    label = { Text(entry.label) },
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }

        SectionCard("Ngày") {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                (1..trip.dayCount).forEach { d ->
                    FilterChip(
                        selected = day == d,
                        onClick = { day = d },
                        label = { Text("Ngày $d") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }

        OutlinedTextField(
            value = note,
            onValueChange = { note = it },
            label = { Text("Ghi chú") },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), Arrangement.End) {
            TextButton(onClick = onDismiss) { Text("Huỷ") }
            Button(
                enabled = canSubmit,
                onClick = {
                    busy = true
                    error = null
                    val newExpense = NewExpense(
                        tripId = trip.id,
                        payerMemberId = payerId,
                        type = type,
                        category = category.id,
                        amount = amount,
                        note = note.takeIf { it.isNotBlank() },
                        tripDay = day,
                    )
                    onSubmit(newExpense, buildShares()) { failure ->
                        busy = false
                        error = failure
                    }
                },
            ) { Text("Lưu") }
        }
    }
}
```

- [ ] **Step 2: Verify the equal split**

Run the app, add a GROUP expense of `100000` with all members selected.
Expected: it appears in the feed, and the listed per-member shares sum to exactly `100.000 ₫` (with 3 members: 33.334 + 33.333 + 33.333).

- [ ] **Step 3: Verify the itemized split**

Add a "Trả hộ" expense with different amounts per person (e.g. 40.000 / 65.000).
Expected: total shows 105.000 ₫, the feed lists each person's own amount, and the payer's own line is their own item price — not an equal share.

- [ ] **Step 4: Verify the self-paid case creates no debt**

Add a "Cá nhân — tự trả" expense of 25.000.
Expected: it appears in the feed with a single share row for the payer. Check with MCP `execute_sql` that exactly one `expense_shares` row exists for it.

- [ ] **Step 5: Verify the amount/shares invariant holds server-side**

```sql
select e.id, e.amount, sum(s.amount) as share_total
from expenses e join expense_shares s on s.expense_id = e.id
group by e.id, e.amount
having e.amount <> sum(s.amount);
```

Expected: **zero rows**. Any row here means the split is losing or inventing money — stop and fix before continuing.

- [ ] **Step 6: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/ui
git commit -m "feat: add expense entry for group, self-paid and itemized splits"
```

---

## Task 14: Dashboard screen

**Files:**
- Modify/Create: `shared/src/commonMain/kotlin/com/example/dalat/ui/DashboardScreen.kt` (replacing the Task 12 stub)

**Interfaces:**
- Consumes: `TripUiState.stats` (Task 12 / Task 6), `SectionCard`, `MoneyText`.
- Produces: `DashboardScreen(state: TripUiState)`.

- [ ] **Step 1: Implement the screen**

```kotlin
package com.example.dalat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.dalat.ui.components.MoneyText
import com.example.dalat.ui.components.SectionCard

@Composable
private fun StatRow(label: String, amount: Long, total: Long) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text(label)
            MoneyText(amount)
        }
        LinearProgressIndicator(
            progress = { if (total > 0) (amount.toFloat() / total.toFloat()) else 0f },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
fun DashboardScreen(state: TripUiState) {
    val stats = state.stats
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionCard("Tổng chi cả nhóm") {
            Text(
                com.example.dalat.domain.formatVnd(stats.total),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text("${state.expenses.size} khoản · ${state.members.size} thành viên")
        }

        SectionCard("Theo ngày") {
            if (stats.byDay.isEmpty()) Text("Chưa có dữ liệu")
            stats.byDay.forEach { (day, amount) -> StatRow("Ngày $day", amount, stats.total) }
        }

        SectionCard("Theo hạng mục") {
            if (stats.byCategory.isEmpty()) Text("Chưa có dữ liệu")
            stats.byCategory.entries
                .sortedByDescending { it.value }
                .forEach { (category, amount) -> StatRow(category.label, amount, stats.total) }
        }

        SectionCard("Mỗi người tiêu bao nhiêu") {
            Text(
                "Tính theo phần mình dùng, không phải tiền đã ứng ra.",
                style = MaterialTheme.typography.bodySmall,
            )
            state.members.forEach { member ->
                StatRow(member.displayName, stats.spendPerMember[member.id] ?: 0L, stats.total)
            }
        }
    }
}
```

- [ ] **Step 2: Verify against the feed**

With several expenses entered, open the Thống kê tab.
Expected: "Tổng chi cả nhóm" equals the sum of the amounts shown in the feed; the "Theo ngày" values sum to the same total; each member's consumption row is non-zero for anyone who has a share.

Cross-check with MCP `execute_sql`:

```sql
select sum(amount) from expenses;
select trip_day, sum(amount) from expenses group by trip_day order by trip_day;
```

- [ ] **Step 3: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/ui/DashboardScreen.kt
git commit -m "feat: add trip dashboard with day, category and member breakdowns"
```

---

## Task 15: Settle-up screen

**Files:**
- Modify/Create: `shared/src/commonMain/kotlin/com/example/dalat/ui/SettleUpScreen.kt` (replacing the Task 12 stub)

**Interfaces:**
- Consumes: `TripUiState.balances` / `.settlements` / `.isPaid` (Task 12), `formatVnd`, `SectionCard`, `MoneyText`.
- Produces: `SettleUpScreen(state: TripUiState, onMarkPaid: (fromMemberId: String, toMemberId: String, isPaid: Boolean) -> Unit)`.

- [ ] **Step 1: Implement the screen**

```kotlin
package com.example.dalat.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.dalat.domain.formatVnd
import com.example.dalat.ui.components.MoneyText
import com.example.dalat.ui.components.SectionCard

private val Owed = Color(0xFF1B5E20)
private val Owes = Color(0xFFB3261E)

@Composable
fun SettleUpScreen(state: TripUiState, onMarkPaid: (String, String, Boolean) -> Unit) {
    var showDetail by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionCard("Số dư từng người") {
            state.balances.sortedByDescending { it.net }.forEach { balance ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    Arrangement.SpaceBetween,
                    Alignment.CenterVertically,
                ) {
                    Column {
                        Text(state.memberName(balance.memberId))
                        Text(
                            "Đã ứng ${formatVnd(balance.paid)} · Đã dùng ${formatVnd(balance.owed)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        MoneyText(
                            balance.net,
                            color = if (balance.net >= 0) Owed else Owes,
                            bold = true,
                        )
                        Text(
                            when {
                                balance.net > 0 -> "được nhận"
                                balance.net < 0 -> "cần trả"
                                else -> "xong"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                HorizontalDivider()
            }
        }

        SectionCard("Cần chuyển khoản") {
            if (state.settlements.isEmpty()) {
                Text("Cả nhóm đã cân bằng, không ai phải trả ai.")
            } else {
                Text(
                    "${state.settlements.size} giao dịch là đủ để cân bằng cả nhóm.",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.settlements.forEach { transaction ->
                    val paid = state.isPaid(transaction.fromMemberId, transaction.toMemberId)
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        Arrangement.SpaceBetween,
                        Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${state.memberName(transaction.fromMemberId)} → ${state.memberName(transaction.toMemberId)}",
                            )
                            MoneyText(transaction.amount, bold = true)
                        }
                        Checkbox(
                            checked = paid,
                            onCheckedChange = {
                                onMarkPaid(transaction.fromMemberId, transaction.toMemberId, it)
                            },
                        )
                    }
                }
            }
        }

        TextButton(onClick = { showDetail = !showDetail }) {
            Text(if (showDetail) "Ẩn chi tiết" else "Xem chi tiết cách tính")
        }

        if (showDetail) {
            SectionCard("Chi tiết từng khoản") {
                state.expenses.forEach { expense ->
                    Text(
                        "${formatVnd(expense.amount)} · ${state.memberName(expense.payerMemberId)} trả" +
                            (expense.note?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    state.sharesOf(expense.id).filter { it.amount > 0 }.forEach { share ->
                        Row(Modifier.fillMaxWidth().padding(start = 12.dp), Arrangement.SpaceBetween) {
                            Text(state.memberName(share.memberId), style = MaterialTheme.typography.bodySmall)
                            MoneyText(share.amount)
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify the settlement against a hand calculation**

With a two-member trip: member A pays a 300.000 group expense split equally, member B pays a 100.000 group expense split equally.
Expected: A's net is +100.000, B's net is −100.000, and exactly one transaction is listed: B → A, 100.000 ₫.

- [ ] **Step 3: Verify balances always cancel**

Whatever mix of expenses exists, the numbers in "Số dư từng người" must sum to zero, and applying every listed transaction must zero every balance. Confirm with MCP `execute_sql`:

```sql
with paid as (
  select payer_member_id as member_id, sum(amount) as amt from expenses group by 1
), owed as (
  select member_id, sum(amount) as amt from expense_shares group by 1
)
select coalesce(sum(coalesce(p.amt,0) - coalesce(o.amt,0)), 0) as net_total
from trip_members m
left join paid p on p.member_id = m.id
left join owed o on o.member_id = m.id;
```

Expected: `net_total` = 0.

- [ ] **Step 4: Verify the paid checkbox syncs**

Tick a transaction's checkbox, then reload the page (and check in the second browser profile).
Expected: the tick persists and appears for the other member too.

- [ ] **Step 5: Commit**

```bash
git add shared/src/commonMain/kotlin/com/example/dalat/ui/SettleUpScreen.kt
git commit -m "feat: add settle-up screen with minimal transactions and paid tracking"
```

---

## Task 16: Production build and Vercel deploy

Vercel's build image has no supported Gradle/JDK toolchain, so the Wasm bundle is built locally and uploaded prebuilt. Vercel only serves static files here.

**Files:**
- Create: `vercel.json`
- Create: `deploy.sh`
- Modify: `README.md`

- [ ] **Step 1: Produce a production bundle**

Run: `./gradlew :webApp:wasmJsBrowserDistribution`
Then: `ls webApp/build/dist/wasmJs/productionExecutable`
Expected: `index.html`, `webApp.js`, `styles.css` and at least one `.wasm` file. If the path differs, use the real one in the following steps rather than this one.

- [ ] **Step 2: Write the Vercel config**

Create `vercel.json` at the repo root:

```json
{
  "cleanUrls": true,
  "rewrites": [
    { "source": "/(.*)", "destination": "/index.html" }
  ],
  "headers": [
    {
      "source": "/(.*)\\.wasm",
      "headers": [{ "key": "Content-Type", "value": "application/wasm" }]
    }
  ]
}
```

The SPA rewrite matters: the OAuth redirect lands on a URL the static host has no file for, and without the rewrite the sign-in round trip 404s.

- [ ] **Step 3: Write the deploy script**

Create `deploy.sh` and `chmod +x deploy.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

DIST="webApp/build/dist/wasmJs/productionExecutable"

./gradlew :webApp:wasmJsBrowserDistribution
cp vercel.json "$DIST/vercel.json"
cd "$DIST"
vercel deploy --prod
```

- [ ] **Step 4: Human gate — Vercel login**

`vercel deploy` needs an authenticated CLI. Tell the user to run `vercel login` themselves (it opens a browser) and confirm before continuing. Alternatively use the Vercel MCP `deploy_to_vercel` tool.

- [ ] **Step 5: Deploy**

Run: `./deploy.sh`
Expected: a production URL such as `https://dalat-xxxx.vercel.app`. Record it.

- [ ] **Step 6: Allow the deployed origin in Supabase**

In Supabase Dashboard → *Authentication* → *URL Configuration*: set **Site URL** to the Vercel URL and add `https://<vercel-url>/**` to **Redirect URLs** (keep the localhost entry for development). The Google OAuth client needs no change — its redirect URI points at Supabase, not at the app.

- [ ] **Step 7: Verify the deployed app end to end**

On a **phone**, open the Vercel URL and:
1. Sign in with Google → lands back signed in.
2. Join the trip with the 6-character code.
3. Add one expense of each of the three types.
4. Open the same trip on a second device and confirm the new expense shows up without reloading.
5. Check the Quyết toán tab shows a sensible minimal transaction list.

Anything that fails here is a real bug — the app is only "done" when this passes on a phone.

- [ ] **Step 8: Document it**

Replace the scaffold text in `README.md` with: what the app is, the Supabase project ref, how to run locally (`./gradlew :webApp:wasmJsBrowserDevelopmentRun`), how to run tests (`./gradlew :shared:wasmJsTest`), how to deploy (`./deploy.sh`), and the note that `supabase/migrations/0001_init.sql` is the schema source of truth.

- [ ] **Step 9: Commit**

```bash
git add vercel.json deploy.sh README.md
git commit -m "chore: add Vercel static deploy pipeline and project README"
```

---

## Appendix: Things that will bite

- **RLS recursion.** Any new policy that reads `trip_members` must go through `is_trip_member()`, never a direct subquery.
- **`postgresChangeFlow` before `subscribe()`.** Creating a change flow on an already-subscribed channel silently delivers nothing.
- **Shares must reconcile.** `ExpenseRepository.addExpense` requires `shares.values.sum() == expense.amount`. If a new expense type is added, keep that invariant or the settlement math silently drifts.
- **Determinism of `SettlementCalculator.simplify`.** The `settlement_marks` rows key off `(from_member_id, to_member_id)`. Changing the sort order orphans everyone's "đã trả" ticks.
- **Long, not Double.** A single `Double` anywhere in the money path reintroduces rounding drift that the đồng-exact split was written to prevent.
