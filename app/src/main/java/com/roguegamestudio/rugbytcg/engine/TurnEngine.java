package com.roguegamestudio.rugbytcg.engine;

import com.roguegamestudio.rugbytcg.utils.TimeSource;

public class TurnEngine {
    public enum TurnState { PLAYER, AI_THINKING }

    private final TimeSource timeSource;
    private long turnStartElapsedMs = 0L;
    private long turnDurationMs = 10_000L;
    // Multiplayer uses infinite-turn behavior (no auto-timeout); timer state is advisory only.
    private boolean turnTimeoutEnabled = false;
    private boolean turnTimeoutHandled = false;
    private TurnState turnState = TurnState.PLAYER;

    public TurnEngine(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    public void resetTurnTimer() {
        turnStartElapsedMs = timeSource.nowElapsedMs();
        turnTimeoutHandled = false;
    }

    public void resetTurnTimerAtElapsed(long startElapsedMs) {
        if (startElapsedMs <= 0L) {
            resetTurnTimer();
            return;
        }
        turnStartElapsedMs = startElapsedMs;
        turnTimeoutHandled = false;
    }

    public void setTurnState(TurnState state) {
        turnState = state;
    }

    public TurnState getTurnState() {
        return turnState;
    }

    public void setTurnDurationMs(long durationMs) {
        turnDurationMs = durationMs;
    }

    public long getTurnDurationMs() {
        return turnDurationMs;
    }

    public void setTurnTimeoutEnabled(boolean enabled) {
        turnTimeoutEnabled = enabled;
        turnTimeoutHandled = false;
    }

    public boolean isTurnTimeoutEnabled() {
        return turnTimeoutEnabled;
    }

    public long getTurnRemainingMs() {
        if (turnStartElapsedMs <= 0L) return turnDurationMs;
        long now = timeSource.nowElapsedMs();
        return (turnStartElapsedMs + turnDurationMs) - now;
    }

    public long getTurnDisplayRemainingMs() {
        long remaining = getTurnRemainingMs();
        if (remaining <= 0L) return 0L;
        return ((remaining + 999L) / 1000L) * 1000L;
    }

    public void resetTurnTimerFromRemainingMs(long remainingMs) {
        long clamped = Math.max(0L, Math.min(turnDurationMs, remainingMs));
        long elapsedIntoTurn = turnDurationMs - clamped;
        turnStartElapsedMs = timeSource.nowElapsedMs() - elapsedIntoTurn;
        turnTimeoutHandled = false;
    }

    public boolean shouldTimeout() {
        if (!turnTimeoutEnabled) return false;
        if (turnState != TurnState.PLAYER) return false;
        if (turnTimeoutHandled) return false;
        if (getTurnRemainingMs() <= 0L) {
            turnTimeoutHandled = true;
            return true;
        }
        return false;
    }
}
