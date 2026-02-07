-- Online gameplay actions for RugbyTCG2026
-- Adds gameplay action support for submit_match_action and efficient action reads.

-- Remove any pre-existing overloads to avoid PostgREST HTTP 300 ambiguity.
do $$
declare
    fn regprocedure;
begin
    for fn in
        select p.oid::regprocedure
          from pg_proc p
          join pg_namespace n on n.oid = p.pronamespace
         where n.nspname = 'public'
           and p.proname = 'submit_match_action'
    loop
        execute format('drop function if exists %s', fn);
    end loop;
end;
$$;

create index if not exists idx_match_actions_match_seq
    on public.match_actions (match_id, seq);

-- Ensure Realtime can stream match_actions inserts.
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
declare
    v_user_id uuid := auth.uid();
    v_action text := lower(trim(coalesce(p_action_type, '')));
    v_match public.matches%rowtype;
    v_next_seq integer;
begin
    if v_user_id is null then
        return query select false, -1, 'not_authenticated';
        return;
    end if;

    if p_match_id is null then
        return query select false, -1, 'missing_match_id';
        return;
    end if;

    if v_action not in ('player_ready', 'play_card', 'end_turn', 'phase_state', 'resync_request') then
        return query select false, -1, 'invalid_action_type';
        return;
    end if;

    select *
      into v_match
      from public.matches
     where id = p_match_id
     for update;

    if not found then
        return query select false, -1, 'match_not_found';
        return;
    end if;

    if v_match.status not in ('pending', 'active') then
        return query select false, -1, 'match_not_active';
        return;
    end if;

    if v_user_id <> v_match.player_a and v_user_id <> v_match.player_b then
        return query select false, -1, 'not_match_participant';
        return;
    end if;

    select coalesce(max(ma.seq), 0) + 1
      into v_next_seq
      from public.match_actions ma
     where ma.match_id = p_match_id;

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

    if v_match.status = 'pending' and v_action = 'player_ready' then
        update public.matches
           set status = 'active'
         where id = p_match_id;
    end if;

    return query select true, v_next_seq, ''::text;
end;
$$;

grant execute on function public.submit_match_action(uuid, text, jsonb) to authenticated;

alter table public.match_actions enable row level security;

drop policy if exists match_actions_select_participants on public.match_actions;
create policy match_actions_select_participants
on public.match_actions
for select
to authenticated
using (
    exists (
        select 1
          from public.matches m
         where m.id = match_actions.match_id
           and (m.player_a = auth.uid() or m.player_b = auth.uid())
    )
);

-- Ensure PostgREST immediately picks up the function definition change.
notify pgrst, 'reload schema';
