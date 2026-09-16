# Prompt để giao cho AI agent

Copy toàn bộ phần trong khung dưới đây và dán vào agent (Claude Code, Cursor, v.v.)
đang mở tại thư mục `/home/leduc/AndroidStudioProjects`.

---

Build a mobile-first web app that lets five friends on a five-day trip to Đà Lạt
log their expenses together and settle up at the end. I have already written the
design spec and a task-by-task implementation plan — your job is to execute them,
not to redesign.

**Read these two documents first, in full, before writing any code:**

1. `docs/superpowers/specs/2026-09-16-dalat-trip-expense-splitter-design.md` — the
   design spec. It explains the three expense types, the data model, the RLS
   design and the settlement algorithm, and *why* each is shaped that way.
2. `docs/superpowers/plans/2026-09-16-dalat-trip-expense-splitter.md` — the
   implementation plan: 16 tasks, each with exact file paths, real code, real
   verification commands and a commit step.

**Execute the plan task by task, in order.** Each task ends with a verification
step and a commit — actually run the verification and actually make the commit
before moving to the next task. Do not batch several tasks together and do not
skip a verification because the code "looks right". Tick the `- [ ]` checkboxes
in the plan as you go.

**What already exists:** the repo is a Kotlin Multiplatform scaffold
(`rootProject.name = "Dalat"`, Kotlin 2.4.20, Compose Multiplatform 1.12.0,
package `com.example.dalat`) with two modules, `shared` and `webApp`, targeting
`js` and `wasmJs` only. It currently contains nothing but the JetBrains demo
"Click me!" screen. Git is initialised and the spec and plan are committed.

**The hard constraints** (the plan's "Global Constraints" section has the full
list, but these are the ones that cause silent damage if broken):

- Money is VND as `Long` đồng everywhere — Postgres `bigint`, Kotlin `Long`.
  Never `Double`. An expense's shares must sum to exactly the expense amount.
- Ship the `wasmJs` target. Because `js` and `wasmJs` are the only targets, every
  dependency goes in `commonMain`.
- supabase-kt `3.8.0` with Ktor `3.5.1`, kept in lockstep.
- All UI copy is Vietnamese; currency renders as `1.234.567 ₫`.
- Never put a Supabase service role key in this repo. The anon/publishable key in
  client code is fine and intended — RLS is what protects the data.

**Tooling:** you have Supabase MCP tools available. Use them to create the
project, apply the migration, and run the verification SQL in the plan. You also
have Vercel MCP tools for the final deploy.

**Two things you cannot do yourself.** When you reach them, stop, tell me exactly
what to do, and wait:

1. **Task 1, Step 7** — creating the Google OAuth client in Google Cloud Console
   and pasting the client ID/secret into the Supabase dashboard.
2. **Task 16, Step 4** — `vercel login`.

Also stop and ask before creating any billable Supabase or Vercel resource.

**If reality contradicts the plan** — an API signature differs, a Gradle task
name is wrong, a dependency does not resolve — do not silently improvise a
different architecture. Fix the specific detail, say what you changed and why,
and carry on with the plan. If something forces a real design change, stop and
tell me before implementing it.

**Definition of done:** the app is deployed to Vercel, and on a phone I can sign
in with Google, join the trip with a six-character code, add all three kinds of
expense, see a second member's entry appear without reloading, and read a
settle-up screen whose suggested transfers actually zero out everyone's balance.
That is the bar in Task 16, Step 7 — the work is not finished until it passes.

---

## Ghi chú cho bạn (không thuộc prompt)

**Việc bạn cần chuẩn bị trước:**

- Tài khoản Supabase (free tier là đủ).
- Tài khoản Google Cloud để tạo OAuth client (miễn phí).
- Tài khoản Vercel + cài `vercel` CLI (`npm i -g vercel`).

**Chi phí:** Supabase free tier và Vercel Hobby thừa sức cho 5 người / 5 ngày.

**Sau chuyến đi:** schema hỗ trợ nhiều trip, nên lần sau chỉ cần tạo trip mới và
chia lại mã, không phải deploy lại gì.
