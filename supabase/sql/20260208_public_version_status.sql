-- Public version status RPC for exact latest-client checks.
-- This patch extends multiplayer_runtime_config with an explicit latest version
-- and adds a public callable endpoint used by the app menu before auth bootstrap.

alter table public.multiplayer_runtime_config
    add column if not exists latest_client_version integer not null default 2;

update public.multiplayer_runtime_config
   set latest_client_version = greatest(
       coalesce(latest_client_version, 2),
       coalesce(min_supported_client_version, 2)
   )
 where singleton = true;

insert into public.multiplayer_runtime_config (singleton, min_supported_client_version, latest_client_version)
values (true, 2, 2)
on conflict (singleton) do update
    set latest_client_version = excluded.latest_client_version,
        min_supported_client_version = excluded.min_supported_client_version,
        updated_at = now();

create or replace function public.get_client_version_status_v1(
    p_client_version integer default 0
)
returns table (
    accepted boolean,
    reason text,
    latest_client_version integer,
    min_supported_client_version integer,
    up_to_date boolean
)
language plpgsql
security definer
set search_path = public
as $$
declare
    v_latest integer := 2;
    v_min integer := 2;
begin
    select c.latest_client_version, c.min_supported_client_version
      into v_latest, v_min
      from public.multiplayer_runtime_config c
     where c.singleton = true
     limit 1;

    v_latest := coalesce(v_latest, 2);
    v_min := coalesce(v_min, 2);

    if p_client_version is null or p_client_version <= 0 then
        return query select false, 'invalid_client_version', v_latest, v_min, false;
        return;
    end if;

    if p_client_version = v_latest then
        return query select true, ''::text, v_latest, v_min, true;
        return;
    end if;

    return query select false, 'client_upgrade_required', v_latest, v_min, false;
end;
$$;

grant execute on function public.get_client_version_status_v1(integer) to anon;
grant execute on function public.get_client_version_status_v1(integer) to authenticated;

notify pgrst, 'reload schema';
