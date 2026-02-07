package com.roguegamestudio.rugbytcg.engine;

import com.roguegamestudio.rugbytcg.utils.TimeSource;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TurnEngineTest {
    @Test
    public void resetTurnTimerAtElapsed_usesProvidedAnchor() {
        FakeTimeSource time = new FakeTimeSource();
        time.elapsedMs = 2_000L;

        TurnEngine engine = new TurnEngine(time);
        engine.setTurnDurationMs(10_000L);
        engine.resetTurnTimerAtElapsed(1_000L);

        assertEquals(9_000L, engine.getTurnRemainingMs());
    }

    @Test
    public void shouldTimeout_firesOnceAfterAnchoredDeadline() {
        FakeTimeSource time = new FakeTimeSource();
        time.elapsedMs = 2_000L;

        TurnEngine engine = new TurnEngine(time);
        engine.setTurnDurationMs(10_000L);
        engine.resetTurnTimerAtElapsed(1_000L);

        assertFalse(engine.shouldTimeout());

        time.elapsedMs = 11_050L;
        assertTrue(engine.shouldTimeout());
        assertFalse(engine.shouldTimeout());
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
