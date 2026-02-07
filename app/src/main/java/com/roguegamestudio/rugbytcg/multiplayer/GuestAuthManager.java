package com.roguegamestudio.rugbytcg.multiplayer;

import android.content.Context;

import com.roguegamestudio.rugbytcg.BuildConfig;

public class GuestAuthManager {
    private static final long SESSION_EXPIRY_SAFETY_WINDOW_SEC = 60L;

    private final SupabaseService service;
    private final SupabaseSessionStore store;

    public GuestAuthManager(Context context) {
        this.service = new SupabaseService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY);
        this.store = new SupabaseSessionStore(context.getApplicationContext());
    }

    public AuthState ensureGuestSession() throws Exception {
        SupabaseSession session = store.load();

        if (session == null) {
            session = service.signInAnonymously();
            store.save(session);
        } else if (isNearExpiry(session)) {
            try {
                session = service.refreshSession(session.refreshToken);
                store.save(session);
            } catch (Exception refreshFailure) {
                session = service.signInAnonymously();
                store.save(session);
            }
        }

        SupabaseProfile profile = service.fetchProfile(session.accessToken, session.userId);
        return new AuthState(session, profile);
    }

    private boolean isNearExpiry(SupabaseSession session) {
        if (session.expiresAtEpochSeconds <= 0L) return true;
        long now = System.currentTimeMillis() / 1000L;
        return session.expiresAtEpochSeconds <= (now + SESSION_EXPIRY_SAFETY_WINDOW_SEC);
    }

    public static class AuthState {
        public final SupabaseSession session;
        public final SupabaseProfile profile;

        public AuthState(SupabaseSession session, SupabaseProfile profile) {
            this.session = session;
            this.profile = profile;
        }
    }
}
