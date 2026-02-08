-- RugbyTCG2026 multiplayer protocol v2
-- Full-authority transport contract, reconnect-safe kickoff, and app-active presence.

do $$
declare
    fn regprocedure;
begin
    for fn in
        select p.oid::regprocedure
          from pg_proc p
          join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public'
           and p.proname in (
               'join_match_v2',
               'submit_match_action_v2',
               'heartbeat_presence_v2',
               'fetch_actions_since_v2',
               'get_online_count_v2'
           )
    loop
        execute format('drop function if exists %s', fn);
    end loop;
end;
$$;

alter table public.matches
    add column if not exists protocol_version integer not null default 2,
    add column if not exists last_seq integer not null default 0,
    add column if not exists canonical_state jsonb not null default '{}'::jsonb,
    add column if not exists turn_owner text not null default 'player_a',
    add column if not exists turn_started_at timestamptz,
    add column if not exists match_started_at timestamptz,
    add column if not exists paused_at timestamptz,
    add column if not exists paused_accumulated_ms bigint not null default 0,
    add column if not exists awaiting_rekickoff boolean not null default false,
    add column if not exists kickoff_generation integer not null default 0;

create table if not exists public.multiplayer_runtime_config (
    singleton boolean primary key default true,
    min_supported_client_version integer not null default 2,
    updated_at timestamptz not null default now()
);

insert into public.multiplayer_runtime_config (singleton, min_supported_client_version)
values (true, 2)
on conflict (singleton) do nothing;

create table if not exists public.match_presence (
    match_id uuid not null references public.matches(id) on delete cascade,
    user_id uuid not null,
    app_active boolean not null default true,
    last_seen_at timestamptz not null default now(),
    primary key (match_id, user_id)
);

create index if not exists idx_match_presence_match_seen
    on public.match_presence (match_id, last_seen_at desc);

create table if not exists public.online_presence_v2 (
    user_id uuid primary key,
    app_active boolean not null default true,
    last_seen_at timestamptz not null default now(),
    current_match_id uuid null references public.matches(id) on delete set null
);

create index if not exists idx_online_presence_v2_seen
    on public.online_presence_v2 (last_seen_at desc);

create index if not exists idx_match_actions_match_seq
    on public.match_actions (match_id, seq);

create unique index if not exists uq_match_actions_match_seq
    on public.match_actions (match_id, seq);

do $$
begin
    begin
        execute 'alter publication supabase_realtime add table public.match_actions';
    exception
        when duplicate_object then
            null;
    end;
end;
$$;

create or replace function public._client_version_ok_v2(p_client_version integer)
returns boolean
language plpgsql
security definer
set search_path = public
as $$
declare
    v_min integer := 2;
begin
    select c.min_supported_client_version
      into v_min
      from public.multiplayer_runtime_config c
     where c.singleton = true
     limit 1;

    if p_client_version is null then
        return false;
    end if;
    return p_client_version >= coalesce(v_min, 2);
end;
$$;

create or replace function public._active_presence_count_v2(p_match_id uuid)
returns integer
language sql
security definer
set search_path = public
as $$
    select count(*)
      from public.match_presence mp
     where mp.match_id = p_match_id
       and mp.app_active = true
       and mp.last_seen_at >= (now() - interval '20 seconds');
$$;

create or replace function public._effective_match_elapsed_ms_v2(v_match public.matches)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
    v_anchor timestamptz;
    v_elapsed bigint;
begin
    if v_match.match_started_at is null then
        return 0;
    end if;

    v_anchor := coalesce(v_match.paused_at, now());
    v_elapsed := floor(extract(epoch from (v_anchor - v_match.match_started_at)) * 1000)::bigint
                 - coalesce(v_match.paused_accumulated_ms, 0);
    if v_elapsed < 0 then
        v_elapsed := 0;
    end if;
    if v_elapsed > 180000 then
        v_elapsed := 180000;
    end if;
    return v_elapsed;
end;
$$;

create or replace function public._effective_turn_remaining_ms_v2(v_match public.matches)
returns bigint
language plpgsql
security definer
set search_path = public
as $$
declare
    v_anchor timestamptz;
    v_elapsed bigint;
begin
    if v_match.turn_started_at is null then
        return 10000;
    end if;
    v_anchor := coalesce(v_match.paused_at, now());
    v_elapsed := floor(extract(epoch from (v_anchor - v_match.turn_started_at)) * 1000)::bigint;
    if v_elapsed < 0 then
        v_elapsed := 0;
    end if;
    if v_elapsed > 10000 then
        v_elapsed := 10000;
    end if;
    return 10000 - v_elapsed;
end;
$$;

create or replace function public._reconcile_match_pause_v2(p_match_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_match public.matches%rowtype;
    v_active_count integer;
begin
    select *
      into v_match
      from public.matches
     where id = p_match_id
     for update;

    if not found then
        return;
    end if;

    if v_match.status not in ('active', 'pending') then
        return;
    end if;

    v_active_count := public._active_presence_count_v2(p_match_id);
    if v_active_count <= 0 and v_match.paused_at is null then
        update public.matches
           set paused_at = now(),
               awaiting_rekickoff = true,
               kickoff_generation = greatest(v_match.kickoff_generation, 0) + 1
         where id = p_match_id;
    end if;
end;
$$;

create or replace function public.join_match_v2(
    p_match_id uuid,
    p_client_version integer
)
returns table (
    accepted boolean,
    reason text,
    match_id uuid,
    status text,
    player_a uuid,
    player_b uuid,
    your_user_id uuid,
    protocol_version integer,
    last_seq integer,
    turn_owner text,
    turn_remaining_ms bigint,
    match_elapsed_ms bigint,
    awaiting_rekickoff boolean,
    kickoff_generation integer,
    canonical_state jsonb,
    local_kickoff_ready boolean,
    remote_kickoff_ready boolean
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_match public.matches%rowtype;
    v_local_ready boolean := false;
    v_remote_ready boolean := false;
begin
    if v_user_id is null then
        return query select false, 'not_authenticated', null::uuid, ''::text, null::uuid, null::uuid, null::uuid,
                            0, -1, ''::text, 0::bigint, 0::bigint, false, 0, '{}'::jsonb, false, false;
        return;
    end if;

    if not public._client_version_ok_v2(p_client_version) then
        return query select false, 'client_upgrade_required', null::uuid, ''::text, null::uuid, null::uuid, v_user_id,
                            0, -1, ''::text, 0::bigint, 0::bigint, false, 0, '{}'::jsonb, false, false;
        return;
    end if;

    select *
      into v_match
      from public.matches
     where id = p_match_id
     for update;

    if not found then
        return query select false, 'match_not_found', p_match_id, ''::text, null::uuid, null::uuid, v_user_id,
                            2, -1, ''::text, 0::bigint, 0::bigint, false, 0, '{}'::jsonb, false, false;
        return;
    end if;

    if v_user_id <> v_match.player_a and v_user_id <> v_match.player_b then
        return query select false, 'not_match_participant', p_match_id, v_match.status, v_match.player_a, v_match.player_b, v_user_id,
                            v_match.protocol_version, v_match.last_seq, v_match.turn_owner, 0::bigint, 0::bigint,
                            v_match.awaiting_rekickoff, v_match.kickoff_generation, coalesce(v_match.canonical_state, '{}'::jsonb), false, false;
        return;
    end if;

    insert into public.match_presence (match_id, user_id, app_active, last_seen_at)
    values (p_match_id, v_user_id, true, now())
    on conflict (match_id, user_id)
    do update set app_active = true, last_seen_at = excluded.last_seen_at;

    insert into public.online_presence_v2 (user_id, app_active, last_seen_at, current_match_id)
    values (v_user_id, true, now(), p_match_id)
    on conflict (user_id)
    do update set app_active = true,
                  last_seen_at = excluded.last_seen_at,
                  current_match_id = excluded.current_match_id;

    perform public._reconcile_match_pause_v2(p_match_id);

    select *
      into v_match
      from public.matches
     where id = p_match_id;

    if v_match.awaiting_rekickoff and v_match.kickoff_generation > 0 then
        select exists(
            select 1
              from public.match_actions ma
             where ma.match_id = p_match_id
               and ma.action_type = 'kickoff_ready'
               and ma.actor_user_id = v_user_id
               and coalesce((ma.payload ->> 'generation')::integer, -1) = v_match.kickoff_generation
        )
          into v_local_ready;

        select exists(
            select 1
              from public.match_actions ma
             where ma.match_id = p_match_id
               and ma.action_type = 'kickoff_ready'
               and ma.actor_user_id <> v_user_id
               and coalesce((ma.payload ->> 'generation')::integer, -1) = v_match.kickoff_generation
        )
          into v_remote_ready;
    end if;

    return query
    select true,
           ''::text,
           v_match.id,
           v_match.status,
           v_match.player_a,
           v_match.player_b,
           v_user_id,
           v_match.protocol_version,
           coalesce(v_match.last_seq, 0),
           coalesce(v_match.turn_owner, 'player_a'),
           public._effective_turn_remaining_ms_v2(v_match),
           public._effective_match_elapsed_ms_v2(v_match),
           coalesce(v_match.awaiting_rekickoff, false),
           coalesce(v_match.kickoff_generation, 0),
           coalesce(v_match.canonical_state, '{}'::jsonb),
           coalesce(v_local_ready, false),
           coalesce(v_remote_ready, false);
end;
$$;

create or replace function public.submit_match_action_v2(
    p_match_id uuid,
    p_action_type text,
    p_payload jsonb default '{}'::jsonb,
    p_expected_seq integer default null,
    p_client_version integer default 0
)
returns table (
    accepted boolean,
    seq integer,
    reason text,
    last_seq integer,
    turn_owner text,
    turn_remaining_ms bigint,
    match_elapsed_ms bigint,
    awaiting_rekickoff boolean,
    kickoff_generation integer,
    canonical_state jsonb
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_action text := lower(trim(coalesce(p_action_type, '')));
    v_match public.matches%rowtype;
    v_actor_role text;
    v_next_seq integer;
    v_generation integer;
    v_ready_count integer;
    v_player_a_starts boolean := true;
begin
    if v_user_id is null then
        return query select false, -1, 'not_authenticated', -1, ''::text, 0::bigint, 0::bigint, false, 0, '{}'::jsonb;
        return;
    end if;

    if not public._client_version_ok_v2(p_client_version) then
        return query select false, -1, 'client_upgrade_required', -1, ''::text, 0::bigint, 0::bigint, false, 0, '{}'::jsonb;
        return;
    end if;

    if p_match_id is null then
        return query select false, -1, 'missing_match_id', -1, ''::text, 0::bigint, 0::bigint, false, 0, '{}'::jsonb;
        return;
    end if;

    if v_action not in ('match_ready', 'kickoff_ready', 'play_card', 'end_turn', 'phase_state', 'resync_request') then
        return query select false, -1, 'invalid_action_type', -1, ''::text, 0::bigint, 0::bigint, false, 0, '{}'::jsonb;
        return;
    end if;

    select *
      into v_match
      from public.matches
     where id = p_match_id
     for update;

    if not found then
        return query select false, -1, 'match_not_found', -1, ''::text, 0::bigint, 0::bigint, false, 0, '{}'::jsonb;
        return;
    end if;

    if v_user_id <> v_match.player_a and v_user_id <> v_match.player_b then
        return query select false, -1, 'not_match_participant', v_match.last_seq, v_match.turn_owner,
                            public._effective_turn_remaining_ms_v2(v_match),
                            public._effective_match_elapsed_ms_v2(v_match),
                            v_match.awaiting_rekickoff, v_match.kickoff_generation, v_match.canonical_state;
        return;
    end if;

    if v_match.status not in ('pending', 'active') then
        return query select false, -1, 'match_not_active', v_match.last_seq, v_match.turn_owner,
                            public._effective_turn_remaining_ms_v2(v_match),
                            public._effective_match_elapsed_ms_v2(v_match),
                            v_match.awaiting_rekickoff, v_match.kickoff_generation, v_match.canonical_state;
        return;
    end if;

    v_actor_role := case when v_user_id = v_match.player_a then 'player_a' else 'player_b' end;

    if p_expected_seq is not null and p_expected_seq <> coalesce(v_match.last_seq, 0) then
        return query select false, -1, 'seq_conflict', v_match.last_seq, v_match.turn_owner,
                            public._effective_turn_remaining_ms_v2(v_match),
                            public._effective_match_elapsed_ms_v2(v_match),
                            v_match.awaiting_rekickoff, v_match.kickoff_generation, v_match.canonical_state;
        return;
    end if;

    if v_action in ('play_card', 'end_turn') and coalesce(v_match.awaiting_rekickoff, false) then
        return query select false, -1, 'kickoff_required', v_match.last_seq, v_match.turn_owner,
                            public._effective_turn_remaining_ms_v2(v_match),
                            public._effective_match_elapsed_ms_v2(v_match),
                            v_match.awaiting_rekickoff, v_match.kickoff_generation, v_match.canonical_state;
        return;
    end if;

    if v_action in ('play_card', 'end_turn') and coalesce(v_match.turn_owner, 'player_a') <> v_actor_role then
        return query select false, -1, 'not_your_turn', v_match.last_seq, v_match.turn_owner,
                            public._effective_turn_remaining_ms_v2(v_match),
                            public._effective_match_elapsed_ms_v2(v_match),
                            v_match.awaiting_rekickoff, v_match.kickoff_generation, v_match.canonical_state;
        return;
    end if;

    if v_action = 'phase_state' and v_user_id <> v_match.player_a then
        return query select false, -1, 'not_authoritative', v_match.last_seq, v_match.turn_owner,
                            public._effective_turn_remaining_ms_v2(v_match),
                            public._effective_match_elapsed_ms_v2(v_match),
                            v_match.awaiting_rekickoff, v_match.kickoff_generation, v_match.canonical_state;
        return;
    end if;

    if v_action = 'kickoff_ready' and not coalesce(v_match.awaiting_rekickoff, false) then
        return query select false, -1, 'not_awaiting_kickoff', v_match.last_seq, v_match.turn_owner,
                            public._effective_turn_remaining_ms_v2(v_match),
                            public._effective_match_elapsed_ms_v2(v_match),
                            v_match.awaiting_rekickoff, v_match.kickoff_generation, v_match.canonical_state;
        return;
    end if;

    v_next_seq := coalesce(v_match.last_seq, 0) + 1;

    if v_action = 'kickoff_ready' then
        v_generation := coalesce((p_payload ->> 'generation')::integer, v_match.kickoff_generation);
        if v_generation <> v_match.kickoff_generation then
            return query select false, -1, 'stale_kickoff_generation', v_match.last_seq, v_match.turn_owner,
                                public._effective_turn_remaining_ms_v2(v_match),
                                public._effective_match_elapsed_ms_v2(v_match),
                                v_match.awaiting_rekickoff, v_match.kickoff_generation, v_match.canonical_state;
            return;
        end if;
        p_payload := coalesce(p_payload, '{}'::jsonb) || jsonb_build_object('generation', v_generation);
    end if;

    insert into public.match_actions (
        match_id,
        seq,
        actor_user_id,
        action_type,
        payload
    ) values (
        p_match_id,
        v_next_seq,
        v_user_id,
        v_action,
        coalesce(p_payload, '{}'::jsonb)
    );

    update public.matches
       set last_seq = v_next_seq
     where id = p_match_id;

    if v_action = 'match_ready' and v_match.status = 'pending' then
        select count(distinct ma.actor_user_id)::integer
          into v_ready_count
          from public.match_actions ma
         where ma.match_id = p_match_id
           and ma.action_type = 'match_ready';

        if v_ready_count >= 2 then
            v_player_a_starts := (get_byte(uuid_send(p_match_id), 15) % 2 = 0);
            update public.matches
               set status = 'active',
                   match_started_at = coalesce(match_started_at, now()),
                   turn_started_at = coalesce(turn_started_at, now()),
                   turn_owner = case when v_player_a_starts then 'player_a' else 'player_b' end,
                   paused_at = coalesce(paused_at, now()),
                   awaiting_rekickoff = true,
                   kickoff_generation = greatest(kickoff_generation, 0) + 1
             where id = p_match_id;
        end if;
    elsif v_action = 'kickoff_ready' then
        select count(distinct ma.actor_user_id)::integer
          into v_ready_count
          from public.match_actions ma
         where ma.match_id = p_match_id
           and ma.action_type = 'kickoff_ready'
           and coalesce((ma.payload ->> 'generation')::integer, -1) = v_match.kickoff_generation;

        if v_ready_count >= 2 then
            update public.matches
               set paused_accumulated_ms = paused_accumulated_ms
                                          + case
                                                when paused_at is null then 0
                                                else floor(extract(epoch from (now() - paused_at)) * 1000)::bigint
                                            end,
                   paused_at = null,
                   awaiting_rekickoff = false,
                   match_started_at = coalesce(match_started_at, now()),
                   turn_started_at = now()
             where id = p_match_id;
        end if;
    elsif v_action = 'end_turn' then
        update public.matches
           set turn_owner = case when v_match.turn_owner = 'player_a' then 'player_b' else 'player_a' end,
               turn_started_at = now()
         where id = p_match_id;
    elsif v_action = 'phase_state' then
        update public.matches
           set canonical_state = coalesce(p_payload, '{}'::jsonb)
         where id = p_match_id;
    end if;

    select *
      into v_match
      from public.matches
     where id = p_match_id;

    return query
    select true,
           v_next_seq,
           ''::text,
           coalesce(v_match.last_seq, 0),
           coalesce(v_match.turn_owner, 'player_a'),
           public._effective_turn_remaining_ms_v2(v_match),
           public._effective_match_elapsed_ms_v2(v_match),
           coalesce(v_match.awaiting_rekickoff, false),
           coalesce(v_match.kickoff_generation, 0),
           coalesce(v_match.canonical_state, '{}'::jsonb);
end;
$$;

create or replace function public.fetch_actions_since_v2(
    p_match_id uuid,
    p_after_seq integer default -1,
    p_page_size integer default 200,
    p_client_version integer default 0
)
returns table (
    seq integer,
    actor_user_id uuid,
    action_type text,
    payload jsonb,
    created_at timestamptz,
    has_more boolean
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_size integer;
begin
    if v_user_id is null then
        return;
    end if;
    if not public._client_version_ok_v2(p_client_version) then
        return;
    end if;

    if p_match_id is null then
        return;
    end if;
    if p_page_size is null or p_page_size < 1 then
        v_size := 200;
    elsif p_page_size > 500 then
        v_size := 500;
    else
        v_size := p_page_size;
    end if;

    if not exists (
        select 1
          from public.matches m
         where m.id = p_match_id
           and (m.player_a = v_user_id or m.player_b = v_user_id)
    ) then
        return;
    end if;

    return query
    with rows as (
        select ma.seq,
               ma.actor_user_id,
               ma.action_type,
               ma.payload,
               ma.created_at
          from public.match_actions ma
         where ma.match_id = p_match_id
           and ma.seq > coalesce(p_after_seq, -1)
         order by ma.seq asc
         limit v_size
    ),
    max_row as (
        select coalesce(max(r.seq), coalesce(p_after_seq, -1)) as max_seq
          from rows r
    ),
    more as (
        select exists(
            select 1
              from public.match_actions ma
             where ma.match_id = p_match_id
               and ma.seq > (select max_seq from max_row)
        ) as has_more
    )
    select r.seq,
           r.actor_user_id,
           r.action_type,
           coalesce(r.payload, '{}'::jsonb),
           r.created_at,
           m.has_more
      from rows r
 cross join more m;
end;
$$;

create or replace function public.heartbeat_presence_v2(
    p_match_id uuid default null,
    p_app_active boolean default true,
    p_client_version integer default 0
)
returns table (
    accepted boolean,
    reason text,
    online_count integer,
    awaiting_rekickoff boolean,
    kickoff_generation integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
    v_match public.matches%rowtype;
begin
    if v_user_id is null then
        return query select false, 'not_authenticated', 0, false, 0;
        return;
    end if;

    if not public._client_version_ok_v2(p_client_version) then
        return query select false, 'client_upgrade_required', 0, false, 0;
        return;
    end if;

    insert into public.online_presence_v2 (user_id, app_active, last_seen_at, current_match_id)
    values (v_user_id, coalesce(p_app_active, true), now(), p_match_id)
    on conflict (user_id)
    do update set app_active = excluded.app_active,
                  last_seen_at = excluded.last_seen_at,
                  current_match_id = excluded.current_match_id;

    if p_match_id is not null then
        if not exists (
            select 1
              from public.matches m
             where m.id = p_match_id
               and (m.player_a = v_user_id or m.player_b = v_user_id)
        ) then
            return query
            select false,
                   'not_match_participant',
                   (select count(*)::integer
                      from public.online_presence_v2 op
                     where op.app_active = true
                       and op.last_seen_at >= (now() - interval '20 seconds')),
                   false,
                   0;
            return;
        end if;

        insert into public.match_presence (match_id, user_id, app_active, last_seen_at)
        values (p_match_id, v_user_id, coalesce(p_app_active, true), now())
        on conflict (match_id, user_id)
        do update set app_active = excluded.app_active, last_seen_at = excluded.last_seen_at;

        perform public._reconcile_match_pause_v2(p_match_id);
        select *
          into v_match
          from public.matches
         where id = p_match_id;
    end if;

    return query
    select true,
           ''::text,
           (select count(*)::integer
              from public.online_presence_v2 op
             where op.app_active = true
               and op.last_seen_at >= (now() - interval '20 seconds')),
           coalesce(v_match.awaiting_rekickoff, false),
           coalesce(v_match.kickoff_generation, 0);
end;
$$;

create or replace function public.get_online_count_v2(
    p_client_version integer default 0
)
returns table (
    accepted boolean,
    reason text,
    online_count integer
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_user_id uuid := auth.uid();
begin
    if v_user_id is null then
        return query select false, 'not_authenticated', 0;
        return;
    end if;
    if not public._client_version_ok_v2(p_client_version) then
        return query select false, 'client_upgrade_required', 0;
        return;
    end if;
    return query
    select true,
           ''::text,
           (select count(*)::integer
              from public.online_presence_v2 op
             where op.app_active = true
               and op.last_seen_at >= (now() - interval '20 seconds'));
end;
$$;

create or replace function public.submit_match_action(
    p_match_id uuid,
    p_action_type text,
    p_payload jsonb default '{}'::jsonb
)
returns table (
    accepted boolean,
    seq integer,
    reason text
)
language plpgsql
security definer
set search_path = public
as $$
begin
    return query select false, -1, 'client_upgrade_required'::text;
end;
$$;

grant execute on function public.join_match_v2(uuid, integer) to authenticated;
grant execute on function public.submit_match_action_v2(uuid, text, jsonb, integer, integer) to authenticated;
grant execute on function public.fetch_actions_since_v2(uuid, integer, integer, integer) to authenticated;
grant execute on function public.heartbeat_presence_v2(uuid, boolean, integer) to authenticated;
grant execute on function public.get_online_count_v2(integer) to authenticated;
grant execute on function public.submit_match_action(uuid, text, jsonb) to authenticated;

alter table public.match_presence enable row level security;
alter table public.online_presence_v2 enable row level security;

drop policy if exists match_presence_select_participants on public.match_presence;
create policy match_presence_select_participants
on public.match_presence
for select
to authenticated
using (
    exists (
        select 1
          from public.matches m
         where m.id = match_presence.match_id
           and (m.player_a = auth.uid() or m.player_b = auth.uid())
    )
);

drop policy if exists online_presence_v2_select_self on public.online_presence_v2;
create policy online_presence_v2_select_self
on public.online_presence_v2
for select
to authenticated
using (user_id = auth.uid());

notify pgrst, 'reload schema';
