-- RugbyTCG2026 hotfix: keep matches.turn_owner in sync with authoritative phase_state payload.
-- This preserves submit_match_action_v2 turn validation consistency for reconnecting clients/bots.

update public.matches
   set turn_owner = lower(trim(canonical_state ->> 'turn_owner'))
 where status in ('pending', 'active')
   and lower(trim(coalesce(canonical_state ->> 'turn_owner', ''))) in ('player_a', 'player_b')
   and coalesce(turn_owner, '') <> lower(trim(canonical_state ->> 'turn_owner'));

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
    v_payload_turn_owner text := '';
    v_payload_source_seq integer := null;
    v_existing_source_seq integer := -1;
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
        v_payload_turn_owner := lower(trim(coalesce(p_payload ->> 'turn_owner', '')));
        if v_payload_turn_owner not in ('player_a', 'player_b') then
            v_payload_turn_owner := '';
        end if;

        begin
            v_payload_source_seq := (p_payload ->> 'source_seq')::integer;
        exception
            when others then
                v_payload_source_seq := null;
        end;

        begin
            v_existing_source_seq := coalesce((v_match.canonical_state ->> 'source_seq')::integer, -1);
        exception
            when others then
                v_existing_source_seq := -1;
        end;

        if v_payload_source_seq is null then
            v_payload_source_seq := v_next_seq;
        end if;

        if v_payload_source_seq >= v_existing_source_seq then
            update public.matches
               set canonical_state = coalesce(p_payload, '{}'::jsonb),
                   turn_owner = case
                                    when v_payload_turn_owner in ('player_a', 'player_b') then v_payload_turn_owner
                                    else turn_owner
                                end
             where id = p_match_id;
        end if;
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

grant execute on function public.submit_match_action_v2(uuid, text, jsonb, integer, integer) to authenticated;

notify pgrst, 'reload schema';
