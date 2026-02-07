package com.roguegamestudio.rugbytcg.engine;

import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.utils.TimeSource;

public class MatchEngine {
    public enum MatchEnd { NONE, HOME_WINS, AWAY_WINS, TIE }

    private final TimeSource timeSource;

    public MatchEngine(TimeSource timeSource) {
        this.timeSource = timeSource;
    }

    public void resetMatch(GameState state, long durationMs) {
        resetMatchAtElapsed(state, durationMs, timeSource.nowElapsedMs());
    }

    public void resetMatchAtElapsed(GameState state, long durationMs, long startElapsedMs) {
        state.homeScore = 0;
        state.awayScore = 0;
        state.matchStartElapsedMs = Math.max(1L, startElapsedMs);
        state.matchDurationMs = durationMs;
        state.matchOver = false;
        state.suddenDeath = false;
    }

    public void realignMatchElapsedMs(GameState state, long elapsedMs) {
        long clamped = Math.max(0L, Math.min(elapsedMs, state.matchDurationMs));
        state.matchStartElapsedMs = timeSource.nowElapsedMs() - clamped;
    }

    public long getMatchElapsedMs(GameState state) {
        if (state.matchStartElapsedMs <= 0L) return 0L;
        long elapsed = timeSource.nowElapsedMs() - state.matchStartElapsedMs;
        return Math.max(0L, Math.min(elapsed, state.matchDurationMs));
    }

    public MatchEnd updateMatchStateFromClock(GameState state) {
        if (state.matchOver) return MatchEnd.NONE;
        if (state.matchStartElapsedMs <= 0L) return MatchEnd.NONE;
        long elapsedMs = timeSource.nowElapsedMs() - state.matchStartElapsedMs;
        if (elapsedMs < state.matchDurationMs) return MatchEnd.NONE;

        if (state.homeScore != state.awayScore) {
            state.matchOver = true;
            return (state.homeScore > state.awayScore) ? MatchEnd.HOME_WINS : MatchEnd.AWAY_WINS;
        }

        state.matchOver = true;
        return MatchEnd.TIE;
    }
}
