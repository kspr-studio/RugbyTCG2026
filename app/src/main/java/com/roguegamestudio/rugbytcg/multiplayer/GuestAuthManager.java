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
            session = signInAndPersist();
        } else if (isNearExpiry(session)) {
            session = refreshOrSignIn(session);
        }

        try {
            SupabaseProfile profile = service.fetchProfile(session.accessToken, session.userId);
            return new AuthState(session, profile);
        } catch (Exception profileFailure) {
            SupabaseSession recovered = recoverSessionAfterProfileFailure(session, profileFailure);
            SupabaseProfile profile = service.fetchProfile(recovered.accessToken, recovered.userId);
            return new AuthState(recovered, profile);
        }
    }

    private SupabaseSession refreshOrSignIn(SupabaseSession session) throws Exception {
        if (session != null && !isBlank(session.refreshToken)) {
            try {
                SupabaseSession refreshed = service.refreshSession(session.refreshToken);
                store.save(refreshed);
                return refreshed;
            } catch (Exception ignored) {
            }
        }
        store.clear();
        return signInAndPersist();
    }

    private SupabaseSession recoverSessionAfterProfileFailure(SupabaseSession session, Exception profileFailure)
            throws Exception {
        if (!isRecoverableSessionFailure(profileFailure)) {
            throw profileFailure;
        }
        return refreshOrSignIn(session);
    }

    private SupabaseSession signInAndPersist() throws Exception {
        SupabaseSession session = service.signInAnonymously();
        store.save(session);
        return session;
    }

    private boolean isNearExpiry(SupabaseSession session) {
        if (session.expiresAtEpochSeconds <= 0L) return true;
        long now = System.currentTimeMillis() / 1000L;
        return session.expiresAtEpochSeconds <= (now + SESSION_EXPIRY_SAFETY_WINDOW_SEC);
    }

    private boolean isRecoverableSessionFailure(Exception failure) {
        if (failure == null || failure.getMessage() == null) return false;
        String lower = failure.getMessage().toLowerCase();
        return lower.contains("401")
                || lower.contains("403")
                || lower.contains("jwt")
                || lower.contains("token")
                || lower.contains("not_authenticated")
                || lower.contains("invalid refresh token")
                || lower.contains("no profile row found for user");
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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
