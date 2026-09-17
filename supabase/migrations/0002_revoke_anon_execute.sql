-- Supabase's default privileges grant EXECUTE on new functions to `anon`,
-- and `revoke ... from public` in 0001 does not remove that explicit grant.
-- The functions already guard with NOT_AUTHENTICATED, so this is defence in
-- depth rather than a fix for a live hole — it clears the
-- anon_security_definer_function_executable advisor.

revoke all on function public.is_trip_member(uuid) from anon;
revoke all on function public.create_trip(text, date, int, text) from anon;
revoke all on function public.join_trip(text, text) from anon;
