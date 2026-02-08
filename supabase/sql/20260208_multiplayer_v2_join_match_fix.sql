-- Hotfix for join_match_v2 ambiguity caused by RETURNS TABLE output column names.
-- Keeps signature/return contract unchanged while fixing match_presence upsert.

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
    on conflict on constraint match_presence_pkey
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

grant execute on function public.join_match_v2(uuid, integer) to authenticated;

notify pgrst, 'reload schema';
