-- CORE DUMP accounts: the database part.
-- Run once in Supabase: SQL Editor → New query → paste all of this → Run.
-- Running it again is safe: it creates what is missing and replaces the functions.
--
-- What it makes:
--   profiles        public part of an account: unique name, room code (for invites), ranked rating
--   saves           private part: one JSON document per account (settings, looks, history, friends)
--   ranked_reports  each player's report of a ranked match
--   ranked_matches  rated matches (made only when both reports agree)
-- Players can only read profiles, read/write their own save and call the functions below; ratings change only
-- inside report_ranked.

-- ------------------------------------------------------------------ tables

create table if not exists public.profiles (
  id uuid primary key references auth.users (id) on delete cascade,
  handle text not null,
  handle_lc text generated always as (lower(handle)) stored,
  code text not null default '',
  elo int not null default 1000,
  peak_elo int not null default 1000,
  ranked_games int not null default 0,
  ranked_wins int not null default 0,
  streak int not null default 0,
  best_streak int not null default 0,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint profiles_handle_shape check (char_length(handle) between 2 and 14 and handle !~ '\s'),
  constraint profiles_code_shape check (code ~ '^[A-Za-z0-9]{0,16}$')
);
create unique index if not exists profiles_handle_lc_key on public.profiles (handle_lc);
create index if not exists profiles_elo_idx on public.profiles (elo desc);

create table if not exists public.saves (
  id uuid primary key references auth.users (id) on delete cascade,
  data jsonb not null default '{}'::jsonb,
  updated_at timestamptz not null default now(),
  constraint saves_size check (pg_column_size(data) < 800000)
);

create table if not exists public.ranked_reports (
  match_id text not null,
  reporter uuid not null references public.profiles (id) on delete cascade,
  opponent uuid not null references public.profiles (id) on delete cascade,
  mine int not null,
  theirs int not null,
  created_at timestamptz not null default now(),
  primary key (match_id, reporter)
);

create table if not exists public.ranked_matches (
  match_id text primary key,
  player_a uuid references public.profiles (id) on delete set null,
  player_b uuid references public.profiles (id) on delete set null,
  a_points int not null,
  b_points int not null,
  a_before int not null,
  a_after int not null,
  b_before int not null,
  b_after int not null,
  played_at timestamptz not null default now()
);
create index if not exists ranked_matches_a_idx on public.ranked_matches (player_a, played_at desc);
create index if not exists ranked_matches_b_idx on public.ranked_matches (player_b, played_at desc);

-- ------------------------------------------------------------------ who may do what

alter table public.profiles enable row level security;
alter table public.saves enable row level security;
alter table public.ranked_reports enable row level security;
alter table public.ranked_matches enable row level security;

revoke all on public.profiles from anon, authenticated;
revoke all on public.saves from anon, authenticated;
revoke all on public.ranked_reports from anon, authenticated;
revoke all on public.ranked_matches from anon, authenticated;

grant select on public.profiles to authenticated;
grant select, insert, update on public.saves to authenticated;

drop policy if exists "profiles: players read" on public.profiles;
create policy "profiles: players read" on public.profiles for select to authenticated using (true);

drop policy if exists "saves: own only" on public.saves;
create policy "saves: own only" on public.saves for all to authenticated
  using (id = (select auth.uid())) with check (id = (select auth.uid()));

-- ------------------------------------------------------------------ helpers

create or replace function public.clean_handle(p text) returns text
language sql immutable set search_path = '' as $$
  select left(regexp_replace(upper(coalesce(p, '')), '\s', '', 'g'), 14);
$$;

create or replace function public.touch_save() returns trigger
language plpgsql set search_path = '' as $$
begin
  new.updated_at := now();
  return new;
end $$;

drop trigger if exists saves_touch on public.saves;
create trigger saves_touch before update on public.saves
  for each row execute function public.touch_save();

-- a profile and an empty save for every new account (the name comes from the sign-up form)
create or replace function public.new_profile() returns trigger
language plpgsql security definer set search_path = '' as $$
declare
  h text := public.clean_handle(new.raw_user_meta_data ->> 'handle');
  tries int := 0;
begin
  if char_length(h) < 2 then h := 'PLAYER'; end if;
  loop
    begin
      insert into public.profiles (id, handle) values (new.id, h);
      exit;
    exception when unique_violation then
      tries := tries + 1;
      if tries > 25 then raise; end if;
      h := left(h, 10) || (1000 + floor(random() * 9000))::int::text;
    end;
  end loop;
  insert into public.saves (id) values (new.id) on conflict (id) do nothing;
  return new;
end $$;

drop trigger if exists on_auth_user_created on auth.users;
create trigger on_auth_user_created after insert on auth.users
  for each row execute function public.new_profile();

-- ------------------------------------------------------------------ functions the app calls

-- is this name still free? (also before signing up)
create or replace function public.handle_free(p_handle text) returns boolean
language sql stable security definer set search_path = '' as $$
  select not exists (select 1 from public.profiles where handle_lc = lower(public.clean_handle(p_handle)));
$$;

-- my profile; made now if the account has none yet (accounts made before this script ran)
create or replace function public.ensure_profile(p_handle text) returns public.profiles
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  r public.profiles%rowtype;
  h text := public.clean_handle(p_handle);
  tries int := 0;
begin
  if me is null then raise exception 'not signed in'; end if;
  select * into r from public.profiles where id = me;
  if found then return r; end if;
  if char_length(h) < 2 then h := 'PLAYER'; end if;
  loop
    begin
      insert into public.profiles (id, handle) values (me, h) returning * into r;
      exit;
    exception when unique_violation then
      tries := tries + 1;
      if tries > 25 then raise; end if;
      h := left(h, 10) || (1000 + floor(random() * 9000))::int::text;
    end;
  end loop;
  insert into public.saves (id) values (me) on conflict (id) do nothing;
  return r;
end $$;

-- a new name: 'ok', 'taken' or 'bad'
create or replace function public.set_handle(p_handle text) returns text
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  h text := public.clean_handle(p_handle);
begin
  if me is null then return 'signed out'; end if;
  if char_length(h) < 2 then return 'bad'; end if;
  if exists (select 1 from public.profiles where handle_lc = lower(h) and id <> me) then return 'taken'; end if;
  update public.profiles set handle = h, updated_at = now() where id = me;
  return 'ok';
exception when unique_violation then
  return 'taken';
end $$;

-- my room code (friends who add me by name invite me there)
create or replace function public.set_code(p_code text) returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null or p_code is null or p_code !~ '^[A-Za-z0-9]{0,16}$' then return; end if;
  update public.profiles set code = p_code, updated_at = now() where id = auth.uid();
end $$;

-- a ranked result. Both players report; the match is rated (Elo, K = 32, floor 100, the same formula as the app)
-- once both reports agree. Answers {state: done|waiting|disputed|limit|no account|bad|signed out, elo, delta}.
create or replace function public.report_ranked(p_match text, p_opp uuid, p_mine int, p_theirs int) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
  me uuid := auth.uid();
  rep public.ranked_reports%rowtype;
  pa public.profiles%rowtype;
  pb public.profiles%rowtype;
  pm public.ranked_matches%rowtype;
  sa float8;
  ea float8;
  eb float8;
  na int;
  nb int;
  pair int;
begin
  if me is null then return jsonb_build_object('state', 'signed out'); end if;
  if p_opp is null or p_opp = me or p_match is null or char_length(p_match) not between 8 and 80 then
    return jsonb_build_object('state', 'bad');
  end if;
  if p_mine is null or p_theirs is null or p_mine < 0 or p_theirs < 0 or p_mine > 1000000 or p_theirs > 1000000 then
    return jsonb_build_object('state', 'bad');
  end if;
  if not exists (select 1 from public.profiles where id = p_opp) then return jsonb_build_object('state', 'no account'); end if;
  if not exists (select 1 from public.profiles where id = me) then return jsonb_build_object('state', 'no account'); end if;

  -- one report at a time per match (the second waits here and then sees the first)
  perform pg_advisory_xact_lock(hashtext('coredump-ranked:' || p_match));

  select * into pm from public.ranked_matches where match_id = p_match;
  if found then
    if pm.player_a = me then
      return jsonb_build_object('state', 'done', 'elo', pm.a_after, 'delta', pm.a_after - pm.a_before);
    elsif pm.player_b = me then
      return jsonb_build_object('state', 'done', 'elo', pm.b_after, 'delta', pm.b_after - pm.b_before);
    else
      return jsonb_build_object('state', 'bad');
    end if;
  end if;

  insert into public.ranked_reports (match_id, reporter, opponent, mine, theirs)
    values (p_match, me, p_opp, p_mine, p_theirs)
    on conflict (match_id, reporter) do nothing;

  select * into rep from public.ranked_reports where match_id = p_match and reporter = p_opp and opponent = me;
  if not found then return jsonb_build_object('state', 'waiting'); end if;
  if rep.mine <> p_theirs or rep.theirs <> p_mine then return jsonb_build_object('state', 'disputed'); end if;
  -- my own report must say the same as what I send now (a second call cannot change it)
  if not exists (select 1 from public.ranked_reports
                 where match_id = p_match and reporter = me and opponent = p_opp and mine = p_mine and theirs = p_theirs) then
    return jsonb_build_object('state', 'disputed');
  end if;

  select count(*) into pair from public.ranked_matches
    where ((player_a = me and player_b = p_opp) or (player_a = p_opp and player_b = me))
      and played_at > now() - interval '1 day';
  if pair >= 10 then return jsonb_build_object('state', 'limit'); end if;

  -- both rows locked in a fixed order (no deadlock with a match the other way round)
  perform 1 from public.profiles where id in (me, p_opp) order by id for update;
  select * into pa from public.profiles where id = me;
  select * into pb from public.profiles where id = p_opp;

  sa := case when p_mine > p_theirs then 1.0 when p_mine < p_theirs then 0.0 else 0.5 end;
  ea := 1.0 / (1.0 + power(10.0::float8, (pb.elo - pa.elo)::float8 / 400.0));
  eb := 1.0 / (1.0 + power(10.0::float8, (pa.elo - pb.elo)::float8 / 400.0));
  na := greatest(100, floor(pa.elo + 32 * (sa - ea) + 0.5)::int);
  nb := greatest(100, floor(pb.elo + 32 * ((1.0 - sa) - eb) + 0.5)::int);

  update public.profiles set
    elo = na,
    peak_elo = greatest(peak_elo, na),
    ranked_games = ranked_games + 1,
    ranked_wins = ranked_wins + case when sa = 1.0 then 1 else 0 end,
    streak = case when sa = 1.0 then streak + 1 else 0 end,
    best_streak = greatest(best_streak, case when sa = 1.0 then streak + 1 else 0 end),
    updated_at = now()
  where id = me;

  update public.profiles set
    elo = nb,
    peak_elo = greatest(peak_elo, nb),
    ranked_games = ranked_games + 1,
    ranked_wins = ranked_wins + case when sa = 0.0 then 1 else 0 end,
    streak = case when sa = 0.0 then streak + 1 else 0 end,
    best_streak = greatest(best_streak, case when sa = 0.0 then streak + 1 else 0 end),
    updated_at = now()
  where id = p_opp;

  insert into public.ranked_matches (match_id, player_a, player_b, a_points, b_points, a_before, a_after, b_before, b_after)
    values (p_match, me, p_opp, p_mine, p_theirs, pa.elo, na, pb.elo, nb);

  return jsonb_build_object('state', 'done', 'elo', na, 'delta', na - pa.elo);
end $$;

-- my last 30 rated matches, newest first
create or replace function public.my_ranked_log()
returns table (opp text, opp_elo int, mine int, theirs int, delta int, rating int, at_ms bigint)
language sql stable security definer set search_path = '' as $$
  select coalesce(p.handle, '?'),
         case when m.player_a = auth.uid() then m.b_before else m.a_before end,
         case when m.player_a = auth.uid() then m.a_points else m.b_points end,
         case when m.player_a = auth.uid() then m.b_points else m.a_points end,
         case when m.player_a = auth.uid() then m.a_after - m.a_before else m.b_after - m.b_before end,
         case when m.player_a = auth.uid() then m.a_after else m.b_after end,
         (extract(epoch from m.played_at) * 1000)::bigint
  from public.ranked_matches m
  left join public.profiles p
    on p.id = case when m.player_a = auth.uid() then m.player_b else m.player_a end
  where m.player_a = auth.uid() or m.player_b = auth.uid()
  order by m.played_at desc
  limit 30;
$$;

-- deletes my account and everything stored with it
create or replace function public.delete_me() returns void
language plpgsql security definer set search_path = '' as $$
begin
  if auth.uid() is null then raise exception 'not signed in'; end if;
  delete from auth.users where id = auth.uid();
end $$;

-- ------------------------------------------------------------------ function rights

revoke execute on function public.ensure_profile(text) from public, anon;
revoke execute on function public.set_handle(text) from public, anon;
revoke execute on function public.set_code(text) from public, anon;
revoke execute on function public.report_ranked(text, uuid, int, int) from public, anon;
revoke execute on function public.my_ranked_log() from public, anon;
revoke execute on function public.delete_me() from public, anon;

grant execute on function public.clean_handle(text) to anon, authenticated;
grant execute on function public.handle_free(text) to anon, authenticated;
grant execute on function public.ensure_profile(text) to authenticated;
grant execute on function public.set_handle(text) to authenticated;
grant execute on function public.set_code(text) to authenticated;
grant execute on function public.report_ranked(text, uuid, int, int) to authenticated;
grant execute on function public.my_ranked_log() to authenticated;
grant execute on function public.delete_me() to authenticated;

-- PostgREST picks up the new functions
notify pgrst, 'reload schema';
