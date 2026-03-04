package com.roguegamestudio.rugbytcg.engine;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.CardId;
import com.roguegamestudio.rugbytcg.PhaseBonus;
import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.audio.SoundController;
import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutCalculator;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;
import com.roguegamestudio.rugbytcg.utils.TimeSource;
import com.roguegamestudio.rugbytcg.utils.UiCallbacks;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class GameControllerAnnouncerEventTest {
    @Test
    public void startNewMatch_emitsMatchStartEvent() {
        CapturingSink sink = new CapturingSink();
        GameController controller = newController(sink);

        controller.startNewMatch();

        assertTrue(sink.containsType(AnnouncerEvent.Type.MATCH_START));
    }

    @Test
    public void playCard_emitsCardPlayedForHome() {
        CapturingSink sink = new CapturingSink();
        GameState state = new GameState();
        state.yourMomentum = 10;
        UiState ui = new UiState();
        Card card = new PlayerCard(
                CardId.FLANKER,
                "Flanker",
                "+1 PWR",
                3,
                1,
                4,
                (ctx, self) -> PhaseBonus.none()
        );
        state.yourHand.add(card);

        GameController controller = new GameController(
                state,
                ui,
                new NoOpLayoutCalculator(),
                new LayoutSpec(),
                new FakeTimeSource(),
                new NoOpUiCallbacks(),
                new SoundController(),
                sink
        );

        controller.playCard(card, true);

        AnnouncerEvent event = sink.lastOfType(AnnouncerEvent.Type.CARD_PLAYED);
        assertEquals(AnnouncerEvent.Side.HOME, event.side);
        assertEquals(CardId.FLANKER, event.cardId);
    }

    @Test
    public void resolvePhase_try_emitsPhaseAndTryEvents() {
        CapturingSink sink = new CapturingSink();
        GameState state = new GameState();
        state.ballPos = 2;
        state.yourBoard.add(new PlayerCard(
                CardId.PROP,
                "Prop",
                "Power",
                4,
                0,
                3,
                (ctx, self) -> PhaseBonus.none()
        ));

        GameController controller = new GameController(
                state,
                new UiState(),
                new NoOpLayoutCalculator(),
                new LayoutSpec(),
                new FakeTimeSource(),
                new NoOpUiCallbacks(),
                new SoundController(),
                sink
        );

        controller.resolvePhase();

        assertTrue(sink.containsType(AnnouncerEvent.Type.PHASE_RESULT));
        AnnouncerEvent tryEvent = sink.lastOfType(AnnouncerEvent.Type.TRY_SCORED);
        assertEquals(AnnouncerEvent.Side.HOME, tryEvent.side);
    }

    @Test
    public void endMatchByScore_emitsMatchEndWinner() {
        CapturingSink sink = new CapturingSink();
        GameState state = new GameState();
        state.homeScore = 20;
        state.awayScore = 10;
        UiState ui = new UiState();

        GameController controller = new GameController(
                state,
                ui,
                new NoOpLayoutCalculator(),
                new LayoutSpec(),
                new FakeTimeSource(),
                new NoOpUiCallbacks(),
                new SoundController(),
                sink
        );

        controller.endMatchByScore();

        AnnouncerEvent event = sink.lastOfType(AnnouncerEvent.Type.MATCH_END);
        assertEquals(AnnouncerEvent.Side.HOME, event.side);
    }

    @Test
    public void endMatchTie_emitsMatchEndTie() {
        CapturingSink sink = new CapturingSink();
        GameController controller = newController(sink);

        controller.endMatchTie();

        AnnouncerEvent event = sink.lastOfType(AnnouncerEvent.Type.MATCH_END);
        assertEquals(AnnouncerEvent.Side.NONE, event.side);
    }

    private GameController newController(CapturingSink sink) {
        return new GameController(
                new GameState(),
                new UiState(),
                new NoOpLayoutCalculator(),
                new LayoutSpec(),
                new FakeTimeSource(),
                new NoOpUiCallbacks(),
                new SoundController(),
                sink
        );
    }

    private static final class CapturingSink implements AnnouncerSink {
        private final List<AnnouncerEvent> events = new ArrayList<>();

        @Override
        public void onAnnouncerEvent(AnnouncerEvent event) {
            if (event != null) events.add(event);
        }

        boolean containsType(AnnouncerEvent.Type type) {
            for (AnnouncerEvent event : events) {
                if (event.type == type) return true;
            }
            return false;
        }

        AnnouncerEvent lastOfType(AnnouncerEvent.Type type) {
            for (int i = events.size() - 1; i >= 0; i--) {
                AnnouncerEvent event = events.get(i);
                if (event.type == type) return event;
            }
            throw new AssertionError("No event for type " + type + ". Count=" + events.size());
        }
    }

    private static final class FakeTimeSource implements TimeSource {
        long uptimeMs = 1000L;

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

    private static final class NoOpLayoutCalculator extends LayoutCalculator {
        NoOpLayoutCalculator() {
            super(null);
        }

        @Override
        public void layoutAll(LayoutSpec spec, GameState state) {
        }
    }
}
