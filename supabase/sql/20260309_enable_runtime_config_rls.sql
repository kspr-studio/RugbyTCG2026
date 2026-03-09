-- Resolve Supabase lint warning: public.multiplayer_runtime_config exposed without RLS.
-- This table is intended to be read via SECURITY DEFINER RPCs, not directly by clients.

alter table public.multiplayer_runtime_config
    enable row level security;

revoke all on table public.multiplayer_runtime_config from anon;
revoke all on table public.multiplayer_runtime_config from authenticated;

notify pgrst, 'reload schema';
