package com.roguegamestudio.rugbytcg.multiplayer;

public final class PhaseStateSubmissionPolicy {
    private PhaseStateSubmissionPolicy() {
    }

    public static boolean shouldEagerlySubmitAuthoritativePhaseState(String actionType) {
        if (actionType == null) return false;
        String normalized = actionType.trim();
        return "play_card".equalsIgnoreCase(normalized);
    }
}
