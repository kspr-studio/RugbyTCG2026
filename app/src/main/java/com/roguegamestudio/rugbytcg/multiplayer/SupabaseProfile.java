package com.roguegamestudio.rugbytcg.multiplayer;

public class SupabaseProfile {
    public final String userId;
    public final String publicId;
    public final String username;
    public final String displayName;
    public final boolean isGuest;

    public SupabaseProfile(String userId, String publicId, String username, String displayName, boolean isGuest) {
        this.userId = userId;
        this.publicId = publicId;
        this.username = username;
        this.displayName = displayName;
        this.isGuest = isGuest;
    }
}
