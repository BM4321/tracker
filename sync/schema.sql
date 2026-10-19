-- Orb sync schema for Supabase (Postgres). Run once in the SQL editor of a new project.
-- Each device signs in with the same email/password; row-level security keeps rows per user.

create table if not exists public.sessions (
  id          text primary key,              -- uuid generated on the device
  user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  device      text not null,                 -- stable id of the device that recorded it
  platform    text not null check (platform in ('mac', 'android')),
  app_id      text not null,                 -- bundle id / package name
  app_name    text not null,
  rule_id     text,
  category    text not null check (category in ('good', 'bad', 'neutral')),
  started_at  bigint not null,               -- unix ms
  ended_at    bigint not null,               -- unix ms; grows while the session is open
  updated_at  timestamptz not null default now()
);
create index if not exists sessions_user_updated on public.sessions (user_id, updated_at);
create index if not exists sessions_user_started on public.sessions (user_id, started_at);

create table if not exists public.rules (
  user_id          uuid not null default auth.uid() references auth.users (id) on delete cascade,
  id               text not null,
  name             text not null,
  category         text not null check (category in ('good', 'bad', 'neutral')),
  daily_limit_min  integer,
  mac              text[] not null default '{}',
  android          text[] not null default '{}',
  deleted          boolean not null default false,
  updated_at       bigint not null,          -- unix ms set by the editing device; last writer wins
  primary key (user_id, id)
);

-- updated_at on sessions is the server's clock, so pull cursors never miss a row
create or replace function public.touch_updated_at() returns trigger language plpgsql as $$
begin
  new.updated_at := now();
  return new;
end $$;
drop trigger if exists sessions_touch on public.sessions;
create trigger sessions_touch before insert or update on public.sessions
  for each row execute function public.touch_updated_at();

-- a rule edit only lands if it is newer than what the server holds
create or replace function public.rules_lww() returns trigger language plpgsql as $$
begin
  if new.updated_at < old.updated_at then
    return old;
  end if;
  return new;
end $$;
drop trigger if exists rules_lww on public.rules;
create trigger rules_lww before update on public.rules
  for each row execute function public.rules_lww();

alter table public.sessions enable row level security;
alter table public.rules enable row level security;

drop policy if exists "own sessions" on public.sessions;
create policy "own sessions" on public.sessions
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());

drop policy if exists "own rules" on public.rules;
create policy "own rules" on public.rules
  for all using (user_id = auth.uid()) with check (user_id = auth.uid());
