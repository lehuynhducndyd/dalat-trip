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
