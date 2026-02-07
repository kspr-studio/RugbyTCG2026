package com.roguegamestudio.rugbytcg.multiplayer;

import android.content.Context;
import android.content.SharedPreferences;

public class SupabaseSessionStore {
    private static final String PREFS = "supabase_auth";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_REFRESH_TOKEN = "refresh_token";
    private static final String KEY_EXPIRES_AT = "expires_at";
    private static final String KEY_USER_ID = "user_id";

    private final SharedPreferences prefs;

    public SupabaseSessionStore(Context context) {
        this.prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void save(SupabaseSession session) {
        if (session == null) return;
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, session.accessToken)
                .putString(KEY_REFRESH_TOKEN, session.refreshToken)
                .putLong(KEY_EXPIRES_AT, session.expiresAtEpochSeconds)
                .putString(KEY_USER_ID, session.userId)
                .apply();
    }

    public SupabaseSession load() {
        String accessToken = prefs.getString(KEY_ACCESS_TOKEN, null);
        String refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null);
        long expiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L);
        String userId = prefs.getString(KEY_USER_ID, null);
        if (isBlank(accessToken) || isBlank(refreshToken) || isBlank(userId)) {
            return null;
        }
        return new SupabaseSession(accessToken, refreshToken, expiresAt, userId);
    }

    public void clear() {
        prefs.edit().clear().apply();
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
