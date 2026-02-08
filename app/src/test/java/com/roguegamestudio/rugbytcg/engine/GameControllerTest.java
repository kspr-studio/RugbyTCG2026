package com.roguegamestudio.rugbytcg.engine;

import com.roguegamestudio.rugbytcg.audio.SoundController;
import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.utils.TimeSource;
import com.roguegamestudio.rugbytcg.utils.UiCallbacks;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GameControllerTest {
    @Test
    public void endMatchByScore_setsStickyBannerWithTwoSecondHold() {
        FakeTimeSource time = new FakeTimeSource();
        time.uptimeMs = 10_000L;

        GameState state = new GameState();
        state.homeScore = 15;
        state.awayScore = 10;
        UiState ui = new UiState();

        GameController controller = new GameController(
                state,
                ui,
                null,
                null,
                time,
                new NoOpUiCallbacks(),
                new SoundController()
        );

        controller.endMatchByScore();

        assertTrue(state.matchOver);
        assertEquals("HOME WINS 15-10", state.bannerText);
        assertEquals(Long.MAX_VALUE, state.bannerUntilMs);
        assertTrue(ui.matchWinBannerSticky);
        assertEquals(12_000L, ui.matchBannerDismissAllowedAtMs);
        assertTrue(controller.isMatchBannerDismissBlocked());

        time.uptimeMs = 12_000L;
        assertFalse(controller.isMatchBannerDismissBlocked());
    }

    @Test
    public void endMatchByScore_usesOpponentLabelWhenAwayWins() {
        FakeTimeSource time = new FakeTimeSource();
        time.uptimeMs = 20_000L;

        GameState state = new GameState();
        state.homeScore = 5;
        state.awayScore = 10;
        UiState ui = new UiState();
        ui.localPlayerLabel = "You";
        ui.opponentPlayerLabel = "AI";

        GameController controller = new GameController(
                state,
                ui,
                null,
                null,
                time,
                new NoOpUiCallbacks(),
                new SoundController()
        );

        controller.endMatchByScore();

        assertEquals("AI WINS 5-10", state.bannerText);
    }

    private static final class FakeTimeSource implements TimeSource {
        long uptimeMs;

        @Override
        public long nowUptimeMs() {
            return uptimeMs;
        }

        @Override
        public long nowElapsedMs() {
            return uptimeMs;
        }
    }

    private static final class NoOpUiCallbacks implements UiCallbacks {
        @Override
        public void invalidate() {
        }

        @Override
        public void postInvalidateOnAnimation() {
        }

        @Override
        public void post(Runnable r) {
        }

        @Override
        public void postDelayed(Runnable r, long delayMs) {
        }

        @Override
        public void removeCallbacks(Runnable r) {
        }

        @Override
        public boolean isAttachedToWindow() {
            return true;
        }
    }
}
