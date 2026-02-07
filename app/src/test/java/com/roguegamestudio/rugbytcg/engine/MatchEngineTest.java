package com.roguegamestudio.rugbytcg.engine;

import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.utils.TimeSource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class MatchEngineTest {
    @Test
    public void resetMatchAtElapsed_anchorsElapsedClock() {
        FakeTimeSource time = new FakeTimeSource();
        time.elapsedMs = 5_000L;

        MatchEngine engine = new MatchEngine(time);
        GameState state = new GameState();
        engine.resetMatchAtElapsed(state, 180_000L, 1_000L);

        assertEquals(4_000L, engine.getMatchElapsedMs(state));
    }

    @Test
    public void updateMatchStateFromClock_usesAnchoredStart() {
        FakeTimeSource time = new FakeTimeSource();
        time.elapsedMs = 5_000L;

        MatchEngine engine = new MatchEngine(time);
        GameState state = new GameState();
        engine.resetMatchAtElapsed(state, 3_000L, 1_000L);

        state.homeScore = 10;
        state.awayScore = 5;

        MatchEngine.MatchEnd end = engine.updateMatchStateFromClock(state);
        assertEquals(MatchEngine.MatchEnd.HOME_WINS, end);
    }

    private static final class FakeTimeSource implements TimeSource {
        long elapsedMs;

        @Override
        public long nowUptimeMs() {
            return elapsedMs;
        }

        @Override
        public long nowElapsedMs() {
            return elapsedMs;
        }
    }
}
