package com.roguegamestudio.rugbytcg.multiplayer;

public class SupabaseSession {
    public final String accessToken;
    public final String refreshToken;
    public final long expiresAtEpochSeconds;
    public final String userId;

    public SupabaseSession(String accessToken, String refreshToken, long expiresAtEpochSeconds, String userId) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.expiresAtEpochSeconds = expiresAtEpochSeconds;
        this.userId = userId;
    }
}
