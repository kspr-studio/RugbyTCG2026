package com.roguegamestudio.rugbytcg.engine;

import android.media.ToneGenerator;
import android.view.MotionEvent;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.CardId;
import com.roguegamestudio.rugbytcg.PhaseBonus;
import com.roguegamestudio.rugbytcg.PlayCard;
import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.StarterDeck;
import com.roguegamestudio.rugbytcg.TacticCard;
import com.roguegamestudio.rugbytcg.ai.AiController;
import com.roguegamestudio.rugbytcg.audio.SoundController;
import com.roguegamestudio.rugbytcg.core.CardSnapshot;
import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.core.LogEntry;
import com.roguegamestudio.rugbytcg.tutorial.TutorialController;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutCalculator;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;
import com.roguegamestudio.rugbytcg.utils.TimeSource;
import com.roguegamestudio.rugbytcg.utils.UiCallbacks;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

public class GameController implements AiController.Delegate {
    public interface OnlineActionListener {
        void onLocalPlayCard(CardId cardId);
        void onLocalEndTurn();
        void onLocalKickoff();
    }

    private static final int BALL_MIN = -3;
    private static final int BALL_MAX = 3;
    private static final int TRY_POINTS = 5;
    private static final int HAND_MAX = 7;
    private static final long MATCH_DURATION_MS = 3L * 60L * 1000L;
    private static final long MATCH_START_ANNOUNCE_WINDOW_MS = 1500L;

    private final GameState state;
    private final UiState ui;
    private final RulesEngine rules;
    private final MatchEngine matchEngine;
    private final TurnEngine turnEngine;
    private final AiController aiController;
    private final LayoutCalculator layoutCalculator;
    private final LayoutSpec layoutSpec;
    private final UiCallbacks uiCallbacks;
    private final TimeSource timeSource;
    private final SoundController sound;
    private final AnnouncerSink announcer;
    private final Random rng = new Random();
    private TutorialController tutorial;

    private boolean kickoffPending = false;
    private boolean kickoffResponseResolveOnPlayerEnd = false;
    private boolean skipNextPlayerDraw = false;
    private boolean skipNextOppDraw = false;
    private boolean playerStarts = true;
    private boolean currentPhaseStartsPlayer = true;
    private boolean nextPhaseStartsPlayer = true;
    private boolean onlineInitialKickoffPending = false;
    private boolean onlineInitialKickoffStarterHome = true;
    private boolean onlineLocalIsPlayerA = true;
    private boolean tutorialMode = false;
    private boolean onlineGameplayMode = false;
    private boolean matchStartAnnounced = false;
    private OnlineActionListener onlineActionListener;

    public GameController(GameState state,
                          UiState ui,
                          LayoutCalculator layoutCalculator,
                          LayoutSpec layoutSpec,
                          TimeSource timeSource,
                          UiCallbacks uiCallbacks,
                          SoundController sound) {
        this(
                state,
                ui,
                layoutCalculator,
                layoutSpec,
                timeSource,
                uiCallbacks,
                sound,
                AnnouncerSink.NO_OP
        );
    }

    public GameController(GameState state,
                          UiState ui,
                          LayoutCalculator layoutCalculator,
                          LayoutSpec layoutSpec,
                          TimeSource timeSource,
                          UiCallbacks uiCallbacks,
                          SoundController sound,
                          AnnouncerSink announcer) {
        this.state = state;
        this.ui = ui;
        this.layoutCalculator = layoutCalculator;
        this.layoutSpec = layoutSpec;
        this.timeSource = timeSource;
        this.uiCallbacks = uiCallbacks;
        this.sound = sound;
        this.announcer = announcer != null ? announcer : AnnouncerSink.NO_OP;
        this.rules = new RulesEngine(BALL_MIN, BALL_MAX);
        this.matchEngine = new MatchEngine(timeSource);
        this.turnEngine = new TurnEngine(timeSource);
        this.aiController = new AiController(state, rules);
    }

    public void setTutorialController(TutorialController tutorial) {
        this.tutorial = tutorial;
    }

    public RulesEngine getRules() {
        return rules;
    }

    public MatchEngine getMatchEngine() {
        return matchEngine;
    }

    public TurnEngine getTurnEngine() {
        return turnEngine;
    }

    public boolean isTutorialMode() {
        return tutorialMode;
    }

    public void setTutorialMode(boolean tutorialMode) {
        this.tutorialMode = tutorialMode;
    }

    public boolean isOnlineGameplayMode() {
        return onlineGameplayMode;
    }

    public void setOnlineActionListener(OnlineActionListener listener) {
        this.onlineActionListener = listener;
    }

    public void configureOnlineMatch(String matchId, boolean localIsPlayerA) {
        onlineLocalIsPlayerA = localIsPlayerA;
        rng.setSeed(computeOnlineSeed(matchId));
    }

    public void setPlayerLabels(String localLabel, String opponentLabel) {
        ui.localPlayerLabel = normalizeLabel(localLabel, "HOME");
        ui.opponentPlayerLabel = normalizeLabel(opponentLabel, "AWAY");
    }

    public void tick() {
        if (!tutorialMode) {
            MatchEngine.MatchEnd end = matchEngine.updateMatchStateFromClock(state);
            if (end != MatchEngine.MatchEnd.NONE) {
                if (end == MatchEngine.MatchEnd.HOME_WINS || end == MatchEngine.MatchEnd.AWAY_WINS) {
                    endMatchByScore();
                } else {
                    endMatchTie();
                }
            }
            if (turnEngine.shouldTimeout()) {
                onEndTurn();
            }
        }
    }

    public void startNewMatch() {
        onlineGameplayMode = false;
        onlineInitialKickoffPending = false;
        ui.onlineInitialKickoffPending = false;
        ui.onlineKickoffWaiting = false;
        ui.onlineActionAckPending = false;
        ui.onlineKickoffGeneration = 0;
        ui.matchBannerDismissAllowedAtMs = 0L;
        matchStartAnnounced = false;
        rng.setSeed(System.nanoTime());
        setPlayerLabels("HOME", "AWAY");
        ui.matchWinBannerSticky = false;
        state.hideBanner();
        matchEngine.resetMatch(state, MATCH_DURATION_MS);

        playerStarts = rng.nextBoolean();
        long now = timeSource.nowUptimeMs();
        showBanner(playerStarts ? "HEADS - GOING FIRST" : "TAILS - GOING SECOND", now, 1200);
        announceMatchStart();

        ui.playLog.clear();
        ui.logScrollY = 0f;
        ui.showLog = false;
        ui.showInspect = false;
        ui.inspectCard = null;
        ui.inspectOpponent = false;

        startNewRound(playerStarts);
    }

    public void startOnlineMatch(boolean localStarts, long matchStartEpochMs) {
        startOnlineMatch(localStarts, matchStartEpochMs, false);
    }

    public void startOnlineMatch(boolean localStarts, long matchStartEpochMs, boolean waitForInitialKickoff) {
        tutorialMode = false;
        onlineGameplayMode = true;
        onlineInitialKickoffPending = false;
        ui.onlineInitialKickoffPending = false;
        ui.onlineKickoffWaiting = false;
        ui.onlineActionAckPending = false;
        ui.matchBannerDismissAllowedAtMs = 0L;
        matchStartAnnounced = false;
        ui.matchWinBannerSticky = false;
        state.hideBanner();
        matchEngine.resetMatchAtElapsed(state, MATCH_DURATION_MS, epochToElapsed(matchStartEpochMs));

        ui.playLog.clear();
        ui.logScrollY = 0f;
        ui.showLog = false;
        ui.showInspect = false;
        ui.inspectCard = null;
        ui.inspectOpponent = false;

        if (!waitForInitialKickoff && isFreshMatchStartWindow()) {
            announceMatchStart();
        }
        startNewRound(localStarts, matchStartEpochMs, waitForInitialKickoff);
    }

    public void startTutorialMatch() {
        onlineGameplayMode = false;
        onlineInitialKickoffPending = false;
        ui.onlineInitialKickoffPending = false;
        ui.onlineKickoffWaiting = false;
        ui.onlineActionAckPending = false;
        ui.onlineKickoffGeneration = 0;
        ui.matchBannerDismissAllowedAtMs = 0L;
        matchStartAnnounced = false;
        setPlayerLabels("HOME", "AWAY");
        tutorialMode = true;
        state.homeScore = 0;
        state.awayScore = 0;
        state.matchStartElapsedMs = timeSource.nowElapsedMs();
        state.matchDurationMs = MATCH_DURATION_MS;
        state.matchOver = false;
        state.suddenDeath = false;

        ui.playLog.clear();
        ui.logScrollY = 0f;
        ui.showLog = false;
        ui.showInspect = false;
        ui.inspectCard = null;
        ui.inspectOpponent = false;

        state.yourHand.clear();
        state.oppHand.clear();
        state.yourBoard.clear();
        state.oppBoard.clear();

        state.ballPos = 0;
        state.youWonLastPhase = false;
        state.youLostLastPhase = false;

        state.yourMomentum = 8;
        state.oppMomentum = 8;
        state.nextTurnMomentumBonusYou = 0;
        state.nextTurnMomentumBonusOpp = 0;
        ui.playFlashCard = null;
        ui.playFlashLabel = "";
        ui.playFlashUntilMs = 0L;
        ui.playFlashSourceCard = null;
        ui.playFlashHasTarget = false;
        ui.burnFlashLabel = "BURNING RANDOM CARD";

        state.resetPhaseTemps();
        state.activeTacticYou = null;
        state.activeTacticOpp = null;

        Card playerCard = findCardInStarterDeck(CardId.FLANKER);
        Card playCard = findCardInStarterDeck(CardId.QUICK_PASS);
        Card tacticCard = findCardInStarterDeck(CardId.TIGHT_PLAY);
        if (playerCard != null) state.yourHand.add(playerCard);
        if (playCard != null) state.yourHand.add(playCard);
        if (tacticCard != null) state.yourHand.add(tacticCard);

        Card oppCard = findCardInStarterDeck(CardId.PROP);
        if (oppCard != null) state.oppHand.add(oppCard);

        turnEngine.resetTurnTimer();
        turnEngine.setTurnState(TurnEngine.TurnState.PLAYER);
        announceMatchStart();

        requestLayoutAndInvalidate();
    }

    public void startNewRound(boolean homeStarts) {
        startNewRound(homeStarts, -1L, false);
    }

    private void startNewRound(boolean homeStarts, long firstTurnEpochMs) {
        startNewRound(homeStarts, firstTurnEpochMs, false);
    }

    private void startNewRound(boolean homeStarts, long firstTurnEpochMs, boolean deferInitialKickoff) {
        if (state.matchOver) return;

        List<Card> pool = buildCardPoolFromStarterDeck();

        state.yourHand.clear();
        state.oppHand.clear();
        state.yourBoard.clear();
        state.oppBoard.clear();

        state.ballPos = 0;
        state.youWonLastPhase = false;
        state.youLostLastPhase = false;

        state.yourMomentum = 8;
        state.oppMomentum = 8;
        state.nextTurnMomentumBonusYou = 0;
        state.nextTurnMomentumBonusOpp = 0;
        ui.playFlashCard = null;
        ui.playFlashLabel = "";
        ui.playFlashUntilMs = 0L;
        ui.playFlashSourceCard = null;
        ui.playFlashHasTarget = false;
        ui.burnFlashLabel = "BURNING RANDOM CARD";

        state.resetPhaseTemps();
        state.activeTacticYou = null;
        state.activeTacticOpp = null;

        state.driveUsedThisTurnYou = false;
        state.driveUsedThisTurnOpp = false;

        if (onlineGameplayMode) {
            dealOpeningHandsOnline(pool);
        } else {
            for (int i = 0; i < 4; i++) state.yourHand.add(copyCard(pool.get(rng.nextInt(pool.size()))));
            for (int i = 0; i < 4; i++) state.oppHand.add(copyCard(pool.get(rng.nextInt(pool.size()))));
        }

        requestLayoutAndInvalidate();

        skipNextPlayerDraw = false;
        skipNextOppDraw = false;
        kickoffPending = false;
        kickoffResponseResolveOnPlayerEnd = false;
        nextPhaseStartsPlayer = homeStarts;
        if (homeStarts) skipNextPlayerDraw = true;
        else skipNextOppDraw = true;
        if (onlineGameplayMode && deferInitialKickoff) {
            onlineInitialKickoffPending = true;
            onlineInitialKickoffStarterHome = homeStarts;
            ui.onlineInitialKickoffPending = true;
            ui.onlineKickoffWaiting = false;
            ui.onlineActionAckPending = false;
            state.matchStartElapsedMs = 0L;
            turnEngine.setTurnState(TurnEngine.TurnState.AI_THINKING);
            requestLayoutAndInvalidate();
            return;
        }
        onlineInitialKickoffPending = false;
        ui.onlineInitialKickoffPending = false;
        ui.onlineKickoffWaiting = false;
        ui.onlineActionAckPending = false;
        startPhaseWithStarter(homeStarts, firstTurnEpochMs);
    }

    private List<Card> buildCardPoolFromStarterDeck() {
        List<Card> pool = new ArrayList<>();
        for (Card c : StarterDeck.build()) {
            pool.add(c);
        }
        if (pool.isEmpty()) {
            pool.add(new PlayerCard(CardId.FLANKER, "Flanker", "+1 PWR while contesting.", 3, 1, 4, (m, self) -> {
                PhaseBonus b = new PhaseBonus();
                b.bonusPwr += 1;
                return b;
            }));
        }
        return pool;
    }

    private Card copyCard(Card t) {
        if (t instanceof PlayerCard) {
            PlayerCard src = (PlayerCard) t;
            PlayerCard pc = new PlayerCard(src.id, src.name, src.description, src.pwr, src.skl, src.staMax, src.ability);
            pc.staCurrent = src.staMax;
            return pc;
        }
        if (t instanceof PlayCard) {
            PlayCard src = (PlayCard) t;
            return new PlayCard(src.id, src.name, src.description, src.cost, src.effect);
        }
        if (t instanceof TacticCard) {
            TacticCard src = (TacticCard) t;
            return new TacticCard(src.id, src.name, src.description, src.cost, src.effect);
        }
        return t;
    }

    private Card findCardInStarterDeck(CardId id) {
        for (Card c : StarterDeck.build()) {
            if (c.id == id) return copyCard(c);
        }
        return null;
    }

    private void drawCardForPlayer() {
        if (state.matchOver) return;
        if (state.yourHand.size() >= HAND_MAX) {
            burnRandomHandCard(true);
        }
        List<Card> pool = buildCardPoolFromStarterDeck();
        Card drawn = copyCard(pool.get(rng.nextInt(pool.size())));
        state.yourHand.add(drawn);
        requestLayoutAndInvalidate();
    }

    private void drawCardForAI() {
        if (state.matchOver) return;
        if (state.oppHand.size() >= HAND_MAX) {
            burnRandomHandCard(false);
        }
        List<Card> pool = buildCardPoolFromStarterDeck();
        Card drawn = copyCard(pool.get(rng.nextInt(pool.size())));
        state.oppHand.add(drawn);
        requestLayoutAndInvalidate();
    }

    private void burnRandomHandCard(boolean forYou) {
        List<Card> hand = forYou ? state.yourHand : state.oppHand;
        if (hand.isEmpty()) return;
        int idx = rng.nextInt(hand.size());
        Card burned = hand.remove(idx);
        ui.burnFlashCard = CardSnapshot.from(burned).card;
        ui.burnFlashUntilMs = timeSource.nowUptimeMs() + 1500;
        String owner = forYou ? ui.localPlayerLabel : ui.opponentPlayerLabel;
        ui.burnFlashLabel = (owner == null || owner.trim().isEmpty())
                ? "BURNING RANDOM CARD"
                : owner.toUpperCase() + " BURNING RANDOM CARD";
        uiCallbacks.invalidate();
    }

    public void startPlayerTurn() {
        startPlayerTurn(-1L);
    }

    private void startPlayerTurn(long turnStartEpochMs) {
        if (state.matchOver) return;
        ui.onlineActionAckPending = false;
        if (tutorialMode) {
            turnEngine.resetTurnTimer();
            turnEngine.setTurnState(TurnEngine.TurnState.PLAYER);
            state.driveUsedThisTurnYou = false;
            requestLayoutAndInvalidate();
            return;
        }
        if (onlineGameplayMode && turnStartEpochMs > 0L) {
            turnEngine.resetTurnTimerAtElapsed(epochToElapsed(turnStartEpochMs));
        } else {
            turnEngine.resetTurnTimer();
        }
        state.driveUsedThisTurnYou = false;
        if (skipNextPlayerDraw) {
            skipNextPlayerDraw = false;
        } else {
            drawCardForPlayer();
        }
        state.yourMomentum = 8 + state.nextTurnMomentumBonusYou;
        state.nextTurnMomentumBonusYou = 0;
        turnEngine.setTurnState(TurnEngine.TurnState.PLAYER);
        announce(AnnouncerEvent.Type.TURN_START, AnnouncerEvent.Side.HOME, false);
        requestLayoutAndInvalidate();
    }

    private void startOpponentTurnOnline(long turnStartEpochMs) {
        if (state.matchOver) return;
        ui.onlineActionAckPending = false;
        if (onlineGameplayMode && turnStartEpochMs > 0L) {
            turnEngine.resetTurnTimerAtElapsed(epochToElapsed(turnStartEpochMs));
        } else {
            turnEngine.resetTurnTimer();
        }
        turnEngine.setTurnState(TurnEngine.TurnState.AI_THINKING);
        state.driveUsedThisTurnOpp = false;
        if (skipNextOppDraw) {
            skipNextOppDraw = false;
        } else {
            drawCardForAI();
        }
        state.oppMomentum = 8 + state.nextTurnMomentumBonusOpp;
        state.nextTurnMomentumBonusOpp = 0;
        announce(AnnouncerEvent.Type.TURN_START, AnnouncerEvent.Side.AWAY, false);
        requestLayoutAndInvalidate();
    }

    public void confirmLocalEndTurnAtServerTime(long createdAtEpochMs) {
        if (!onlineGameplayMode) return;
        ui.onlineActionAckPending = false;
        if (state.matchOver) {
            requestLayoutAndInvalidate();
            return;
        }
        if (turnEngine.getTurnState() == TurnEngine.TurnState.PLAYER) {
            if (kickoffResponseResolveOnPlayerEnd) {
                kickoffResponseResolveOnPlayerEnd = false;
                boolean roundEnded = resolvePhase(createdAtEpochMs);
                if (!roundEnded) {
                    startNextPhase(createdAtEpochMs);
                }
            } else {
                startOpponentTurnOnline(createdAtEpochMs);
            }
            return;
        }
        if (createdAtEpochMs > 0L) {
            turnEngine.resetTurnTimerAtElapsed(epochToElapsed(createdAtEpochMs));
        }
        requestLayoutAndInvalidate();
    }

    private void runOpponentTurnAfterDelay(long delayMs) {
        if (state.matchOver) return;
        turnEngine.resetTurnTimer();
        long now = timeSource.nowUptimeMs();
        turnEngine.setTurnState(TurnEngine.TurnState.AI_THINKING);
        state.driveUsedThisTurnOpp = false;
        showBanner("OPPONENT THINKING...", now, delayMs);
        announce(AnnouncerEvent.Type.TURN_START, AnnouncerEvent.Side.AWAY, false);

        uiCallbacks.postDelayed(() -> {
            if (state.matchOver) {
                turnEngine.setTurnState(TurnEngine.TurnState.PLAYER);
                uiCallbacks.invalidate();
                return;
            }
            if (skipNextOppDraw) {
                skipNextOppDraw = false;
            } else {
                drawCardForAI();
            }

            state.oppMomentum = 8 + state.nextTurnMomentumBonusOpp;
            state.nextTurnMomentumBonusOpp = 0;

            aiController.playTurn(this);
        }, delayMs);

        requestLayoutAndInvalidate();
    }

    public void onEndTurn() {
        if (state.matchOver) return;

        if (onlineGameplayMode && onlineInitialKickoffPending) {
            if (ui.onlineKickoffWaiting) return;
            ui.onlineKickoffWaiting = true;
            if (onlineActionListener != null) {
                ui.onlineActionAckPending = true;
                onlineActionListener.onLocalKickoff();
            } else {
                ui.onlineKickoffWaiting = false;
            }
            showBanner("WAITING FOR OTHER PLAYER TO KICKOFF", timeSource.nowUptimeMs(), 1400L);
            requestLayoutAndInvalidate();
            return;
        }

        if (turnEngine.getTurnState() != TurnEngine.TurnState.PLAYER) return;

        if (tutorialMode) {
            if (tutorial != null) tutorial.onEndTurnTutorial();
            return;
        }

        if (onlineGameplayMode) {
            if (onlineActionListener != null) {
                ui.onlineActionAckPending = true;
                onlineActionListener.onLocalEndTurn();
                requestLayoutAndInvalidate();
            }
            return;
        }

        if (kickoffResponseResolveOnPlayerEnd) {
            kickoffResponseResolveOnPlayerEnd = false;
            boolean roundEnded = resolvePhase();
            if (!roundEnded) {
                startNextPhase(-1L);
            }
            return;
        }

        runOpponentTurnAfterDelay(900);
    }

    public boolean resolvePhase() {
        return resolvePhase(-1L);
    }

    private boolean resolvePhase(long nextTurnStartEpochMs) {
        if (state.matchOver) return true;

        MatchEngine.MatchEnd end = matchEngine.updateMatchStateFromClock(state);
        if (end != MatchEngine.MatchEnd.NONE) {
            if (end == MatchEngine.MatchEnd.HOME_WINS || end == MatchEngine.MatchEnd.AWAY_WINS) {
                endMatchByScore();
            } else {
                endMatchTie();
            }
            return true;
        }

        if (ui.showInspect) {
            ui.showInspect = false;
            ui.inspectCard = null;
            ui.inspectFromLog = false;
            ui.inspectOpponent = false;
        }

        long now = timeSource.nowUptimeMs();
        RulesEngine.PhaseResolution resolution = rules.resolvePhase(state);
        boolean phaseProducedTry = resolution.homeTry || resolution.awayTry;

        if (resolution.outcome == RulesEngine.PhaseOutcome.YOU_WIN) {
            showBanner("YOU WIN THE PHASE", now, 1500);
            triggerFlash(0xFF50C878, 420);
            sound.playTone(ToneGenerator.TONE_PROP_BEEP2, 160);
            if (!phaseProducedTry) {
                announce(AnnouncerEvent.Type.PHASE_RESULT, AnnouncerEvent.Side.HOME, false);
            }
        } else if (resolution.outcome == RulesEngine.PhaseOutcome.OPP_WIN) {
            showBanner("YOU LOSE THE PHASE", now, 1500);
            triggerFlash(0xFFDC5050, 420);
            sound.playTone(ToneGenerator.TONE_PROP_NACK, 200);
            if (!phaseProducedTry) {
                announce(AnnouncerEvent.Type.PHASE_RESULT, AnnouncerEvent.Side.AWAY, false);
            }
        } else {
            showBanner("TIE", now, 1500);
            triggerFlash(0xFFA0A0A0, 320);
            sound.playTone(ToneGenerator.TONE_PROP_BEEP, 140);
            if (!phaseProducedTry) {
                announce(AnnouncerEvent.Type.PHASE_RESULT, AnnouncerEvent.Side.NONE, false);
            }
        }

        if (resolution.homeTry) {
            state.homeScore += TRY_POINTS;
            showBanner(ui.localPlayerLabel.toUpperCase() + " TRY! +" + TRY_POINTS, now, 1600);
            triggerFlash(0xFFFFD778, 600);
            sound.playTone(ToneGenerator.TONE_PROP_BEEP2, 220);
            announce(AnnouncerEvent.Type.TRY_SCORED, AnnouncerEvent.Side.HOME, true);

            turnEngine.setTurnState(TurnEngine.TurnState.AI_THINKING);
            final long roundRestartEpochMs = nextTurnStartEpochMs > 0L
                    ? (nextTurnStartEpochMs + 1600L)
                    : -1L;
            long delayMs = 1600L;
            if (roundRestartEpochMs > 0L) {
                delayMs = Math.max(0L, roundRestartEpochMs - System.currentTimeMillis());
            }
            uiCallbacks.postDelayed(() -> {
                if (state.matchOver) return;
                startNewRound(true, roundRestartEpochMs);
            }, delayMs);

            return true;
        } else if (resolution.awayTry) {
            state.awayScore += TRY_POINTS;
            showBanner(ui.opponentPlayerLabel.toUpperCase() + " TRY! +" + TRY_POINTS, now, 1600);
            triggerFlash(0xFFFF8C8C, 600);
            sound.playTone(ToneGenerator.TONE_PROP_NACK, 220);
            announce(AnnouncerEvent.Type.TRY_SCORED, AnnouncerEvent.Side.AWAY, true);

            turnEngine.setTurnState(TurnEngine.TurnState.AI_THINKING);
            final long roundRestartEpochMs = nextTurnStartEpochMs > 0L
                    ? (nextTurnStartEpochMs + 1600L)
                    : -1L;
            long delayMs = 1600L;
            if (roundRestartEpochMs > 0L) {
                delayMs = Math.max(0L, roundRestartEpochMs - System.currentTimeMillis());
            }
            uiCallbacks.postDelayed(() -> {
                if (state.matchOver) return;
                startNewRound(false, roundRestartEpochMs);
            }, delayMs);

            return true;
        }

        state.yourMomentum = 8;
        state.oppMomentum = 8;
        state.resetPhaseTemps();
        chooseNextPhaseStarter(resolution);
        requestLayoutAndInvalidate();
        return false;
    }

    public RulesEngine.PlayResult playCard(Card card, boolean forYou) {
        RulesEngine.PlayResult result = rules.tryPlayCard(state, card, forYou);
        if (!result.success) {
            return result;
        }

        addPlayLog(card, forYou);
        showPlayedCard(card, forYou);

        if (tutorial != null && tutorial.isActive() && forYou && card instanceof PlayerCard && card.id == CardId.FLANKER) {
            tutorial.onFlankerPlayed();
        }

        if (card instanceof PlayerCard) {
            sound.playTone(ToneGenerator.TONE_PROP_BEEP, 120);
            triggerFlash(0xFFECECEC, 140);
        } else if (card instanceof PlayCard) {
            sound.playTone(ToneGenerator.TONE_PROP_BEEP, 120);
            triggerFlash(0xFFB4DCFF, 140);
        } else if (card instanceof TacticCard) {
            sound.playTone(ToneGenerator.TONE_PROP_BEEP, 140);
            triggerFlash(0xFFFFE1A0, 160);
        }
        announce(
                AnnouncerEvent.Type.CARD_PLAYED,
                forYou ? AnnouncerEvent.Side.HOME : AnnouncerEvent.Side.AWAY,
                card,
                false
        );

        if (onlineGameplayMode && forYou && onlineActionListener != null && card.id != null) {
            onlineActionListener.onLocalPlayCard(card.id);
        }
        return result;
    }

    public void applyRemotePlayCard(CardId cardId, long createdAtEpochMs) {
        if (!onlineGameplayMode) return;
        if (state.matchOver) return;
        if (cardId == null) return;
        Card card = findCardInStarterDeck(cardId);
        if (card == null) return;
        applyAcceptedOpponentCard(card);
    }

    public void applyRemoteEndTurn(long createdAtEpochMs) {
        if (!onlineGameplayMode) return;
        if (state.matchOver) return;
        onOpponentTurnComplete(createdAtEpochMs);
    }

    private void applyAcceptedOpponentCard(Card card) {
        if (card == null) return;

        int cost = rules.cardCost(card);
        state.oppMomentum = Math.max(0, state.oppMomentum - cost);
        removeFromOpponentHand(card.id);
        if (card.id == CardId.DRIVE) {
            state.driveUsedThisTurnOpp = true;
        }

        rules.playCardForSide(state, card, false);
        requestLayoutAndInvalidate();
        addPlayLog(card, false);
        showPlayedCard(card, false);

        if (card instanceof PlayerCard) {
            sound.playTone(ToneGenerator.TONE_PROP_BEEP, 120);
            triggerFlash(0xFFECECEC, 140);
        } else if (card instanceof PlayCard) {
            sound.playTone(ToneGenerator.TONE_PROP_BEEP, 120);
            triggerFlash(0xFFB4DCFF, 140);
        } else if (card instanceof TacticCard) {
            sound.playTone(ToneGenerator.TONE_PROP_BEEP, 140);
            triggerFlash(0xFFFFE1A0, 160);
        }
        announce(AnnouncerEvent.Type.CARD_PLAYED, AnnouncerEvent.Side.AWAY, card, false);
    }

    private void removeFromOpponentHand(CardId cardId) {
        if (cardId == null) return;
        for (int i = 0; i < state.oppHand.size(); i++) {
            Card c = state.oppHand.get(i);
            if (c != null && c.id == cardId) {
                state.oppHand.remove(i);
                return;
            }
        }
    }

    private void addPlayLog(Card card, boolean forYou) {
        if (card == null) return;
        String who = forYou ? "YOU: " : "OPP: ";
        String name = card.name != null ? card.name.toUpperCase() : "CARD";
        String time = formatMmSs(matchEngine.getMatchElapsedMs(state));
        ui.playLog.add(0, new LogEntry(time, who + name, CardSnapshot.from(card), !forYou));
        if (ui.playLog.size() > 200) {
            ui.playLog.remove(ui.playLog.size() - 1);
        }
    }

    private void showPlayedCard(Card card, boolean forYou) {
        if (card == null) return;
        ui.playFlashCard = CardSnapshot.from(card).card;
        ui.playFlashOpponent = !forYou;
        ui.playFlashLabel = forYou ? ui.localPlayerLabel : ui.opponentPlayerLabel;
        ui.playFlashSourceCard = card;
        ui.playFlashHasTarget = false;
        ui.playFlashStartMs = timeSource.nowUptimeMs();
        ui.playFlashUntilMs = ui.playFlashStartMs + ui.playFlashHoldMs + ui.playFlashAnimMs;
        uiCallbacks.invalidate();
    }

    public void triggerFlash(int color, long durationMs) {
        ui.flashColor = color;
        ui.flashStartMs = timeSource.nowUptimeMs();
        ui.flashDurationMs = durationMs;
        uiCallbacks.postInvalidateOnAnimation();
    }

    public void showBanner(String text, long now, long durationMs) {
        long until = now + durationMs;
        state.showBanner(text, now, durationMs);
        uiCallbacks.invalidate();
        uiCallbacks.postDelayed(() -> {
            if (state.bannerUntilMs == until) {
                state.hideBanner();
                uiCallbacks.invalidate();
            }
        }, durationMs + 16);
    }

    public void endMatchByScore() {
        if (state.matchOver) return;
        state.matchOver = true;

        kickoffPending = false;
        kickoffResponseResolveOnPlayerEnd = false;
        turnEngine.setTurnState(TurnEngine.TurnState.PLAYER);
        ui.showLog = false;
        ui.showInspect = false;
        ui.inspectCard = null;
        ui.inspectOpponent = false;

        boolean homeWon = state.homeScore > state.awayScore;
        String winnerLabel = homeWon ? ui.localPlayerLabel.toUpperCase() : ui.opponentPlayerLabel.toUpperCase();
        String text = winnerLabel + " WINS " + state.homeScore + "-" + state.awayScore;
        long now = timeSource.nowUptimeMs();
        state.bannerText = text;
        state.bannerUntilMs = Long.MAX_VALUE;
        ui.matchWinBannerSticky = true;
        ui.matchBannerDismissAllowedAtMs = now + 2000L;
        triggerFlash(homeWon ? 0xFF50C878 : 0xFFDC5050, 700);
        sound.playTone(homeWon ? ToneGenerator.TONE_PROP_BEEP2 : ToneGenerator.TONE_PROP_NACK, 220);
        announce(
                AnnouncerEvent.Type.MATCH_END,
                homeWon ? AnnouncerEvent.Side.HOME : AnnouncerEvent.Side.AWAY,
                true
        );
        uiCallbacks.invalidate();
    }

    public void endMatchTie() {
        if (state.matchOver) return;
        state.matchOver = true;

        kickoffPending = false;
        kickoffResponseResolveOnPlayerEnd = false;
        turnEngine.setTurnState(TurnEngine.TurnState.PLAYER);
        ui.showLog = false;
        ui.showInspect = false;
        ui.inspectCard = null;
        ui.inspectOpponent = false;

        long now = timeSource.nowUptimeMs();
        showBanner("MATCH TIED", now, 2200);
        triggerFlash(0xFFA0A0A0, 700);
        sound.playTone(ToneGenerator.TONE_PROP_BEEP, 200);
        announce(AnnouncerEvent.Type.MATCH_END, AnnouncerEvent.Side.NONE, true);
        uiCallbacks.invalidate();
    }

    public boolean isMatchBannerDismissBlocked() {
        if (!state.matchOver) return false;
        if (!ui.matchWinBannerSticky) return false;
        return timeSource.nowUptimeMs() < ui.matchBannerDismissAllowedAtMs;
    }

    public boolean clearMatchBannerIfSticky(MotionEvent e) {
        if (!state.matchOver) return false;
        if (!ui.matchWinBannerSticky) return false;
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return false;
        if (state.bannerText == null || state.bannerText.isEmpty()) return false;
        if (isMatchBannerDismissBlocked()) return true;
        state.hideBanner();
        ui.matchWinBannerSticky = false;
        uiCallbacks.invalidate();
        return true;
    }

    public void requestLayoutAndInvalidate() {
        if (layoutCalculator != null && layoutSpec != null) {
            layoutCalculator.layoutAll(layoutSpec, state);
        }
        uiCallbacks.invalidate();
    }

    private void announce(AnnouncerEvent.Type type, AnnouncerEvent.Side side, boolean critical) {
        announce(type, side, null, critical);
    }

    private void announceMatchStart() {
        if (matchStartAnnounced) return;
        matchStartAnnounced = true;
        announce(AnnouncerEvent.Type.MATCH_START, AnnouncerEvent.Side.NONE, false);
    }

    private boolean isFreshMatchStartWindow() {
        if (state.matchStartElapsedMs <= 0L) return false;
        return matchEngine.getMatchElapsedMs(state) <= MATCH_START_ANNOUNCE_WINDOW_MS;
    }

    private void announce(AnnouncerEvent.Type type, AnnouncerEvent.Side side, Card card, boolean critical) {
        if (tutorialMode) return;
        AnnouncerEvent event = new AnnouncerEvent(
                type,
                side,
                card != null ? card.id : null,
                card != null ? card.name : "",
                state.homeScore,
                state.awayScore,
                state.ballPos,
                matchEngine.getMatchElapsedMs(state),
                critical,
                timeSource.nowUptimeMs()
        );
        announcer.onAnnouncerEvent(event);
    }

    public void applyAuthoritativePhaseState(boolean localIsPlayerA,
                                             int scoreA,
                                             int scoreB,
                                             int canonicalBallPos,
                                             int momentumA,
                                             int momentumB,
                                             boolean localTurn,
                                             long turnRemainingMs,
                                             long matchElapsedMs,
                                             boolean kickoffPendingState,
                                             boolean kickoffResponsePendingState) {
        if (!onlineGameplayMode) return;
        if (state.matchOver) return;
        ui.onlineActionAckPending = false;

        state.homeScore = localIsPlayerA ? scoreA : scoreB;
        state.awayScore = localIsPlayerA ? scoreB : scoreA;
        int localBall = localIsPlayerA ? canonicalBallPos : -canonicalBallPos;
        state.ballPos = Math.max(BALL_MIN, Math.min(BALL_MAX, localBall));
        state.yourMomentum = Math.max(0, localIsPlayerA ? momentumA : momentumB);
        state.oppMomentum = Math.max(0, localIsPlayerA ? momentumB : momentumA);

        kickoffPending = kickoffPendingState;
        kickoffResponseResolveOnPlayerEnd = kickoffResponsePendingState;
        currentPhaseStartsPlayer = kickoffPending ? false : !kickoffResponseResolveOnPlayerEnd;
        nextPhaseStartsPlayer = currentPhaseStartsPlayer;

        matchEngine.realignMatchElapsedMs(state, matchElapsedMs);
        if (turnEngine.isTurnTimeoutEnabled() && turnRemainingMs >= 0L) {
            turnEngine.resetTurnTimerFromRemainingMs(turnRemainingMs);
        }
        turnEngine.setTurnState(localTurn ? TurnEngine.TurnState.PLAYER : TurnEngine.TurnState.AI_THINKING);
        requestLayoutAndInvalidate();
    }

    private long epochToElapsed(long epochMs) {
        if (epochMs <= 0L) return timeSource.nowElapsedMs();
        long nowEpochMs = System.currentTimeMillis();
        long deltaMs = nowEpochMs - epochMs;
        return timeSource.nowElapsedMs() - deltaMs;
    }

    private String formatMmSs(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : String.valueOf(seconds));
    }

    public boolean isKickoffPending() {
        return kickoffPending;
    }

    public boolean isKickoffResponseResolveOnPlayerEnd() {
        return kickoffResponseResolveOnPlayerEnd;
    }

    public boolean isOnlineInitialKickoffPending() {
        return onlineGameplayMode && onlineInitialKickoffPending;
    }

    public void setOnlineKickoffGeneration(int generation) {
        ui.onlineKickoffGeneration = Math.max(0, generation);
    }

    public int getOnlineKickoffGeneration() {
        return Math.max(0, ui.onlineKickoffGeneration);
    }

    public void clearOnlineActionAckPending() {
        ui.onlineActionAckPending = false;
        requestLayoutAndInvalidate();
    }

    public void markOnlineInitialKickoffSubmitted() {
        if (!onlineGameplayMode || !onlineInitialKickoffPending) return;
        ui.onlineKickoffWaiting = true;
        ui.onlineActionAckPending = false;
        requestLayoutAndInvalidate();
    }

    public void clearOnlineInitialKickoffWaiting() {
        if (!onlineGameplayMode || !onlineInitialKickoffPending) return;
        ui.onlineKickoffWaiting = false;
        ui.onlineActionAckPending = false;
        requestLayoutAndInvalidate();
    }

    public void completeOnlineInitialKickoff(long kickoffEpochMs) {
        if (!onlineGameplayMode || !onlineInitialKickoffPending) return;
        onlineInitialKickoffPending = false;
        ui.onlineInitialKickoffPending = false;
        ui.onlineKickoffWaiting = false;
        ui.onlineActionAckPending = false;

        long elapsedSinceKickoff = 0L;
        if (kickoffEpochMs > 0L) {
            elapsedSinceKickoff = Math.max(0L, System.currentTimeMillis() - kickoffEpochMs);
        }
        matchEngine.realignMatchElapsedMs(state, elapsedSinceKickoff);
        if (isFreshMatchStartWindow()) {
            announceMatchStart();
        }
        startPhaseWithStarter(onlineInitialKickoffStarterHome, kickoffEpochMs);
    }

    private void chooseNextPhaseStarter(RulesEngine.PhaseResolution resolution) {
        if (resolution == null) return;
        if (resolution.outcome == RulesEngine.PhaseOutcome.YOU_WIN) {
            nextPhaseStartsPlayer = true;
        } else if (resolution.outcome == RulesEngine.PhaseOutcome.OPP_WIN) {
            nextPhaseStartsPlayer = false;
        } else {
            nextPhaseStartsPlayer = !currentPhaseStartsPlayer;
        }
    }

    private void startNextPhase(long turnStartEpochMs) {
        startPhaseWithStarter(nextPhaseStartsPlayer, turnStartEpochMs);
    }

    private void startPhaseWithStarter(boolean playerStartsPhase, long turnStartEpochMs) {
        currentPhaseStartsPlayer = playerStartsPhase;
        kickoffPending = !playerStartsPhase;
        kickoffResponseResolveOnPlayerEnd = false;
        if (playerStartsPhase) {
            startPlayerTurn(turnStartEpochMs);
            return;
        }
        if (onlineGameplayMode) {
            startOpponentTurnOnline(turnStartEpochMs);
        } else {
            runOpponentTurnAfterDelay(900);
        }
    }

    private String normalizeLabel(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private void dealOpeningHandsOnline(List<Card> pool) {
        List<Card> handA = new ArrayList<>();
        List<Card> handB = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            handA.add(copyCard(pool.get(rng.nextInt(pool.size()))));
        }
        for (int i = 0; i < 4; i++) {
            handB.add(copyCard(pool.get(rng.nextInt(pool.size()))));
        }
        if (onlineLocalIsPlayerA) {
            state.yourHand.addAll(handA);
            state.oppHand.addAll(handB);
        } else {
            state.yourHand.addAll(handB);
            state.oppHand.addAll(handA);
        }
    }

    private long computeOnlineSeed(String matchId) {
        if (matchId == null || matchId.trim().isEmpty()) {
            return 0x24CF5A8D13L;
        }
        String value = matchId.trim();
        try {
            UUID uuid = UUID.fromString(value);
            return uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        } catch (Exception ignored) {
        }
        long h = value.hashCode();
        return (h << 32) ^ (h * 2654435761L);
    }

    @Override
    public boolean playCard(Card card) {
        RulesEngine.PlayResult result = playCard(card, false);
        return result.success;
    }

    @Override
    public int computeSideTotal(boolean you) {
        return rules.computeSideTotal(state, you);
    }

    @Override
    public void layoutAndInvalidate() {
        requestLayoutAndInvalidate();
    }

    @Override
    public long getPlayFlashDelayMs() {
        long now = timeSource.nowUptimeMs();
        return Math.max(0L, ui.playFlashUntilMs - now);
    }

    @Override
    public void postDelayed(Runnable r, long delayMs) {
        uiCallbacks.postDelayed(r, delayMs);
    }

    @Override
    public void onAiTurnComplete() {
        onOpponentTurnComplete(-1L);
    }

    private void onOpponentTurnComplete(long nextTurnStartEpochMs) {
        if (kickoffPending) {
            kickoffPending = false;
            kickoffResponseResolveOnPlayerEnd = true;
            startPlayerTurn(nextTurnStartEpochMs);
            return;
        }
        boolean roundEnded = resolvePhase(nextTurnStartEpochMs);
        if (!roundEnded) {
            startNextPhase(nextTurnStartEpochMs);
        }
    }
}
