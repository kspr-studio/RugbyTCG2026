package com.roguegamestudio.rugbytcg;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import com.roguegamestudio.rugbytcg.assets.CardArtCache;
import com.roguegamestudio.rugbytcg.assets.TextureCache;
import com.roguegamestudio.rugbytcg.audio.AnnouncerController;
import com.roguegamestudio.rugbytcg.audio.SoundController;
import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.engine.AnnouncerSink;
import com.roguegamestudio.rugbytcg.engine.GameController;
import com.roguegamestudio.rugbytcg.engine.TurnEngine;
import com.roguegamestudio.rugbytcg.multiplayer.GuestAuthManager;
import com.roguegamestudio.rugbytcg.multiplayer.PhaseStateSubmissionPolicy;
import com.roguegamestudio.rugbytcg.multiplayer.SupabaseProfile;
import com.roguegamestudio.rugbytcg.multiplayer.SupabaseRealtimeClient;
import com.roguegamestudio.rugbytcg.multiplayer.SupabaseService;
import com.roguegamestudio.rugbytcg.tutorial.TutorialController;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.ui.input.DragState;
import com.roguegamestudio.rugbytcg.ui.input.InputController;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutCalculator;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;
import com.roguegamestudio.rugbytcg.ui.render.GameRenderer;
import com.roguegamestudio.rugbytcg.ui.render.RenderContext;
import com.roguegamestudio.rugbytcg.utils.SystemTimeSource;
import com.roguegamestudio.rugbytcg.utils.TimeSource;
import com.roguegamestudio.rugbytcg.utils.UiCallbacks;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class GameView extends View {
    private static final String TAG = "GameView";
    private static final long UI_HEARTBEAT_MS = 1000L;
    private static final long ONLINE_MATCH_POLL_MS = 1800L;
    private static final long ONLINE_ACTION_FALLBACK_POLL_MS = 800L;
    private static final long ONLINE_ACTION_SAFETY_POLL_MS = 2500L;
    private static final int ONLINE_ACTION_PAGE_SIZE = 200;
    private static final int ONLINE_ACTION_MAX_PAGES_PER_POLL = 8;
    private static final long ONLINE_REALTIME_RECONNECT_MIN_MS = 300L;
    private static final long ONLINE_REALTIME_RECONNECT_MAX_MS = 2500L;
    private static final long ONLINE_RESYNC_REQUEST_MIN_GAP_MS = 1200L;
    private static final long ONLINE_PRESENCE_HEARTBEAT_MS = 7000L;
    private static final long ONLINE_MATCH_END_RETURN_DELAY_MS = 4500L;
    private static final long ONLINE_MATCH_END_RETURN_DELAY_NO_AUDIO_MS = 2200L;

    public interface TutorialListener {
        void onTutorialFinished();
    }

    public interface MenuListener {
        void onExitToMenu();
    }

    private final GameState state = new GameState();
    private final UiState ui = new UiState();
    private final LayoutSpec layout = new LayoutSpec();
    private final LayoutCalculator layoutCalculator;
    private final DragState dragState = new DragState();
    private final CardArtCache cardArtCache = new CardArtCache();
    private final TextureCache textureCache = new TextureCache();
    private final RenderContext renderContext;
    private final GameRenderer renderer;
    private final TimeSource timeSource = new SystemTimeSource();
    private final SoundController sound = new SoundController();
    private final AnnouncerController announcer;
    private final GameController controller;
    private final TutorialController tutorial = new TutorialController();
    private final UiCallbacks uiCallbacks;
    private final float density;
    private final boolean initialTutorialMode;
    private final String onlineMatchId;
    private final String onlineOpponentLabel;
    private final boolean onlineMode;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Paint loadingTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint loadingSubTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AtomicInteger assetLoadGeneration = new AtomicInteger(0);
    private final GuestAuthManager guestAuthManager;
    private final SupabaseService supabaseService;
    private final TreeMap<Integer, SupabaseService.MatchAction> onlineActionBuffer = new TreeMap<>();

    private ExecutorService assetLoader;
    private ExecutorService onlineExecutor;
    private boolean assetsReady = false;
    private boolean assetsFailed = false;
    private boolean assetsLoading = false;
    private boolean gameStarted = false;
    private boolean onlineForfeitHandled = false;
    private boolean onlineMatchEndedHandled = false;
    private boolean onlineInitInProgress = false;
    private boolean onlineInitFailed = false;
    private String onlineInitError = "";
    private boolean onlineGameplayReady = false;
    // Last seq hint from server responses; used for expected_seq on submits.
    private int onlineLastActionSeq = -1;
    // Last action seq actually applied in-order from action stream.
    private int onlineLastAppliedActionSeq = -1;
    private String onlineLocalUserId = null;
    private String onlineAuthoritativeUserId = null;
    private boolean onlineLocalIsPlayerA = false;
    private boolean onlineRealtimeConnected = false;
    private boolean onlineRealtimeReconnectScheduled = false;
    private boolean onlineRealtimeForceRefresh = false;
    private long onlineRealtimeReconnectDelayMs = ONLINE_REALTIME_RECONNECT_MIN_MS;
    private long onlineLastResyncRequestElapsedMs = 0L;
    private boolean onlineKickoffLocalReady = false;
    private boolean onlineKickoffRemoteReady = false;
    private boolean onlineKickoffStarted = false;
    private int onlineKickoffGeneration = 0;
    private boolean onlineReturnToMenuScheduled = false;
    private volatile boolean onlineActionPollInFlight = false;
    private volatile boolean onlineActionRepollRequested = false;
    private volatile boolean onlineStatusPollInFlight = false;
    private volatile GuestAuthManager.AuthState onlineAuthState = null;
    private SupabaseRealtimeClient onlineRealtimeClient = null;
    private InputController input;

    private TutorialListener tutorialListener;
    private MenuListener menuListener;

    private final Runnable uiHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;
            if (gameStarted && (assetsReady || assetsFailed)) {
                controller.tick();
                if (onlineMode && state.matchOver && !onlineForfeitHandled && !onlineMatchEndedHandled) {
                    onlineMatchEndedHandled = true;
                    onlineGameplayReady = false;
                    shutdownOnlineRealtimeClient();
                    scheduleOnlineReturnToMenu(getOnlineMatchEndReturnDelayMs());
                }
            }
            invalidate();
            postDelayed(this, UI_HEARTBEAT_MS);
        }
    };

    private final Runnable onlineMatchPoll = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;
            if (onlineMode && !onlineForfeitHandled && !onlineMatchEndedHandled) {
                pollOnlineMatchStatus();
                postDelayed(this, ONLINE_MATCH_POLL_MS);
            }
        }
    };

    private final Runnable onlineActionPoll = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;
            if (onlineMode && !onlineForfeitHandled && !onlineMatchEndedHandled) {
                if (gameStarted && onlineGameplayReady) {
                    pollOnlineMatchActions();
                }
                long next = onlineRealtimeConnected ? ONLINE_ACTION_SAFETY_POLL_MS : ONLINE_ACTION_FALLBACK_POLL_MS;
                postDelayed(this, next);
            }
        }
    };

    private final Runnable onlinePresenceHeartbeat = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;
            if (onlineMode && onlineGameplayReady && !onlineForfeitHandled && !onlineMatchEndedHandled) {
                submitOnlinePresenceHeartbeat(true);
                postDelayed(this, ONLINE_PRESENCE_HEARTBEAT_MS);
            }
        }
    };

    private final Runnable onlineRealtimeReconnect = new Runnable() {
        @Override
        public void run() {
            if (!isAttachedToWindow()) return;
            if (!onlineMode || !onlineGameplayReady || onlineForfeitHandled || onlineMatchEndedHandled) {
                onlineRealtimeReconnectScheduled = false;
                onlineRealtimeForceRefresh = false;
                return;
            }
            boolean forceRefresh = onlineRealtimeForceRefresh;
            onlineRealtimeReconnectScheduled = false;
            onlineRealtimeForceRefresh = false;
            connectOnlineRealtime(forceRefresh);
        }
    };

    public GameView(Context context) {
        this(context, false, null, null);
    }

    public GameView(Context context, boolean tutorialMode) {
        this(context, tutorialMode, null, null);
    }

    public GameView(Context context, boolean tutorialMode, String onlineMatchId, String onlineOpponentLabel) {
        super(context);
        initialTutorialMode = tutorialMode;
        this.onlineMatchId = normalize(onlineMatchId);
        this.onlineOpponentLabel = (onlineOpponentLabel == null || onlineOpponentLabel.trim().isEmpty())
                ? "Opponent"
                : onlineOpponentLabel.trim();
        this.onlineMode = this.onlineMatchId != null;
        setBackgroundColor(Color.rgb(15, 30, 20));

        density = getResources().getDisplayMetrics().density;
        layoutCalculator = new LayoutCalculator(getResources());

        renderContext = new RenderContext(getResources(), cardArtCache, textureCache);
        renderer = new GameRenderer(renderContext);
        guestAuthManager = onlineMode ? new GuestAuthManager(context) : null;
        supabaseService = onlineMode ? new SupabaseService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY) : null;
        onlineExecutor = onlineMode ? Executors.newSingleThreadExecutor() : null;

        loadingTextPaint.setColor(Color.WHITE);
        loadingTextPaint.setTextAlign(Paint.Align.CENTER);
        loadingTextPaint.setTextSize(20f * density);
        loadingSubTextPaint.setColor(Color.argb(200, 255, 255, 255));
        loadingSubTextPaint.setTextAlign(Paint.Align.CENTER);
        loadingSubTextPaint.setTextSize(14f * density);

        uiCallbacks = new UiCallbacks() {
            @Override
            public void invalidate() {
                GameView.this.invalidate();
            }

            @Override
            public void postInvalidateOnAnimation() {
                GameView.this.postInvalidateOnAnimation();
            }

            @Override
            public void post(Runnable r) {
                GameView.this.post(r);
            }

            @Override
            public void postDelayed(Runnable r, long delayMs) {
                GameView.this.postDelayed(r, delayMs);
            }

            @Override
            public void removeCallbacks(Runnable r) {
                GameView.this.removeCallbacks(r);
            }

            @Override
            public boolean isAttachedToWindow() {
                return GameView.this.isAttachedToWindow();
            }
        };

        boolean enableAnnouncer = !initialTutorialMode;
        announcer = enableAnnouncer ? new AnnouncerController(context) : null;
        AnnouncerSink announcerSink = announcer != null ? announcer : AnnouncerSink.NO_OP;
        sound.init();
        controller = new GameController(state, ui, layoutCalculator, layout, timeSource, uiCallbacks, sound, announcerSink);
        controller.setTutorialController(tutorial);
        if (onlineMode) {
            controller.setOnlineActionListener(new GameController.OnlineActionListener() {
                @Override
                public void onLocalPlayCard(CardId cardId) {
                    submitOnlinePlayCard(cardId);
                }

                @Override
                public void onLocalEndTurn() {
                    submitOnlineEndTurn();
                }

                @Override
                public void onLocalKickoff() {
                    submitOnlineKickoffReady();
                }
            });
        }

        tutorial.setListener(() -> {
            if (tutorialListener != null) tutorialListener.onTutorialFinished();
        });

        TutorialController.FinishHandler finishHandler = new TutorialController.FinishHandler() {
            @Override
            public void onFinishMenu() {
                tutorial.onTutorialFinished();
                controller.setTutorialMode(false);
                if (menuListener != null) menuListener.onExitToMenu();
            }

            @Override
            public void onFinishStart() {
                tutorial.onTutorialFinished();
                controller.setTutorialMode(false);
                controller.startNewMatch();
            }
        };

        input = new InputController(
                state,
                ui,
                layout,
                controller,
                controller.getTurnEngine(),
                uiCallbacks,
                dragState,
                tutorial,
                finishHandler,
                () -> {
                    if (menuListener != null) menuListener.onExitToMenu();
                },
                density
        );

        startAssetLoadingIfNeeded();
    }

    public void setTutorialListener(TutorialListener listener) {
        this.tutorialListener = listener;
    }

    public void setMenuListener(MenuListener listener) {
        this.menuListener = listener;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(uiHeartbeat);
        post(uiHeartbeat);
        removeCallbacks(onlineMatchPoll);
        removeCallbacks(onlineActionPoll);
        removeCallbacks(onlineRealtimeReconnect);
        removeCallbacks(onlinePresenceHeartbeat);
        if (onlineMode && !onlineForfeitHandled && !onlineMatchEndedHandled) {
            postDelayed(onlineMatchPoll, 800L);
            postDelayed(onlineActionPoll, 900L);
            if (gameStarted && onlineGameplayReady) {
                postDelayed(onlinePresenceHeartbeat, 600L);
            }
            if (gameStarted && onlineGameplayReady && !onlineRealtimeConnected) {
                connectOnlineRealtime(false);
            }
        }

        if (!gameStarted) {
            if (assetsReady || assetsFailed) {
                startGameIfNeeded();
                invalidate();
            } else {
                startAssetLoadingIfNeeded();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(uiHeartbeat);
        removeCallbacks(onlineMatchPoll);
        removeCallbacks(onlineActionPoll);
        removeCallbacks(onlineRealtimeReconnect);
        removeCallbacks(onlinePresenceHeartbeat);
        submitOnlinePresenceHeartbeat(false);
        if (!gameStarted && assetsLoading) {
            assetLoadGeneration.incrementAndGet();
            assetsLoading = false;
            shutdownAssetLoader();
        }
        shutdownOnlineRealtimeClient();
        shutdownOnlineExecutor();
        if (announcer != null) {
            announcer.release();
        }
        sound.release();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        layoutCalculator.onSizeChanged(layout, w, h);
        if (gameStarted) {
            controller.requestLayoutAndInvalidate();
        } else {
            invalidate();
        }
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (!onlineMode) return;
        if (visibility == View.VISIBLE) {
            if (gameStarted && onlineGameplayReady && !onlineForfeitHandled && !onlineMatchEndedHandled) {
                removeCallbacks(onlinePresenceHeartbeat);
                postDelayed(onlinePresenceHeartbeat, 400L);
            }
            return;
        }
        removeCallbacks(onlinePresenceHeartbeat);
        submitOnlinePresenceHeartbeat(false);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!gameStarted) {
            drawLoadingOverlay(canvas);
            return;
        }

        clearDragStateIfNeeded();

        renderer.draw(
                canvas,
                layout,
                state,
                ui,
                dragState,
                controller.getRules(),
                controller.getMatchEngine(),
                controller.getTurnEngine(),
                tutorial
        );

        long now = SystemClock.uptimeMillis();
        boolean needsAnim = dragState.dragging != null
                || (ui.playFlashCard != null && now < ui.playFlashUntilMs)
                || (ui.burnFlashCard != null && now < ui.burnFlashUntilMs)
                || ui.flashDurationMs > 0
                || (tutorial != null && tutorial.isActive() && tutorial.getStep() == TutorialController.TUT_INTRO)
                || hasLowStaminaPulse();
        if (needsAnim) {
            postInvalidateOnAnimation();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!gameStarted) return true;
        return input.onTouchEvent(event);
    }

    private void startAssetLoadingIfNeeded() {
        if (assetsReady || assetsFailed || assetsLoading) return;
        assetsLoading = true;
        final int generation = assetLoadGeneration.incrementAndGet();

        shutdownAssetLoader();
        assetLoader = Executors.newSingleThreadExecutor();
        assetLoader.execute(() -> {
            boolean loadOk = true;
            try {
                cardArtCache.load(getContext().getAssets());
                textureCache.load(getContext().getAssets());
            } catch (Throwable t) {
                loadOk = false;
            }

            final boolean result = loadOk;
            mainHandler.post(() -> {
                if (generation != assetLoadGeneration.get()) return;
                assetsLoading = false;
                assetsReady = result;
                assetsFailed = !result;
                if (isAttachedToWindow()) {
                    startGameIfNeeded();
                    invalidate();
                }
                shutdownAssetLoader();
            });
        });
    }

    private void shutdownAssetLoader() {
        if (assetLoader != null) {
            assetLoader.shutdownNow();
            assetLoader = null;
        }
    }

    private void startGameIfNeeded() {
        if (gameStarted || onlineInitInProgress || onlineInitFailed) return;

        boolean tutorialMode = initialTutorialMode && !onlineMode;
        controller.setTutorialMode(tutorialMode);
        if (tutorialMode) {
            tutorial.start();
            controller.startTutorialMatch();
            gameStarted = true;
        } else {
            tutorial.stop();
            if (onlineMode) {
                bootstrapOnlineGameplay();
                return;
            }
            controller.startNewMatch();
            gameStarted = true;
        }
    }

    private void bootstrapOnlineGameplay() {
        if (!onlineMode || guestAuthManager == null || supabaseService == null || onlineExecutor == null) {
            onlineInitInProgress = false;
            onlineInitFailed = true;
            onlineInitError = "Online services unavailable";
            invalidate();
            return;
        }
        if (onlineExecutor.isShutdown()) {
            onlineInitInProgress = false;
            onlineInitFailed = true;
            onlineInitError = "Online worker unavailable";
            invalidate();
            return;
        }

        onlineInitInProgress = true;
        onlineInitFailed = false;
        onlineInitError = "";
        onlineGameplayReady = false;
        onlineLastActionSeq = -1;
        onlineLastAppliedActionSeq = -1;
        onlineActionRepollRequested = false;
        onlineActionBuffer.clear();
        onlineRealtimeConnected = false;
        onlineRealtimeReconnectScheduled = false;
        onlineRealtimeForceRefresh = false;
        onlineRealtimeReconnectDelayMs = ONLINE_REALTIME_RECONNECT_MIN_MS;
        onlineAuthoritativeUserId = null;
        onlineLocalIsPlayerA = false;
        onlineKickoffLocalReady = false;
        onlineKickoffRemoteReady = false;
        onlineKickoffStarted = false;
        onlineKickoffGeneration = 0;
        onlineReturnToMenuScheduled = false;
        shutdownOnlineRealtimeClient();

        boolean submitted = executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(true);
                onlineLocalUserId = auth.session.userId;

                SupabaseService.JoinMatchResult join = supabaseService.joinMatchV2(
                        auth.session.accessToken,
                        onlineMatchId
                );
                if (join == null || !join.accepted) {
                    String reason = join == null ? "join_failed" : normalize(join.reason);
                    throw new IllegalStateException(reason == null ? "join_failed" : reason);
                }

                String localUserId = normalize(join.yourUserId) != null ? join.yourUserId : onlineLocalUserId;
                onlineLocalUserId = localUserId;
                onlineAuthoritativeUserId = normalize(join.playerA);
                final boolean localIsPlayerA = localUserId != null
                        && onlineAuthoritativeUserId != null
                        && localUserId.equals(onlineAuthoritativeUserId);
                onlineLocalIsPlayerA = localIsPlayerA;

                onlineKickoffGeneration = Math.max(0, join.kickoffGeneration);
                onlineKickoffLocalReady = join.localKickoffReady;
                onlineKickoffRemoteReady = join.remoteKickoffReady;
                onlineKickoffStarted = !join.awaitingRekickoff;

                final String turnOwner = normalize(join.turnOwner);
                final boolean localStarts = "player_a".equalsIgnoreCase(turnOwner)
                        ? localIsPlayerA
                        : !localIsPlayerA;
                final long startEpochFinal = join.matchElapsedMs >= 0L
                        ? (System.currentTimeMillis() - join.matchElapsedMs)
                        : System.currentTimeMillis();
                final boolean awaitingRekickoff = join.awaitingRekickoff;
                final int lastSeqFinal = Math.max(-1, join.lastSeq);
                final boolean initialKickoffLocalReady = join.localKickoffReady;
                final JSONObject canonicalState = normalizeJoinCanonicalState(join.canonicalState, turnOwner);
                final long turnRemainingFromJoin = Math.max(0L, join.turnRemainingMs);
                final long matchElapsedFromJoin = Math.max(0L, join.matchElapsedMs);
                Log.i(
                        TAG,
                        "join_match_v2 accepted lastSeq=" + lastSeqFinal
                                + " turnOwner=" + turnOwner
                                + " awaitingRekickoff=" + awaitingRekickoff
                                + " kickoffGen=" + onlineKickoffGeneration
                );

                String localLabel = resolveLocalOnlineLabel(auth);
                String opponentLabelFinal = normalize(onlineOpponentLabel) != null
                        ? onlineOpponentLabel
                        : "Opponent";

                post(() -> {
                    if (!isAttachedToWindow()) {
                        onlineInitInProgress = false;
                        return;
                    }
                    controller.setPlayerLabels(localLabel, opponentLabelFinal);
                    controller.configureOnlineMatch(onlineMatchId, localIsPlayerA);
                    controller.setOnlineKickoffGeneration(onlineKickoffGeneration);
                    controller.startOnlineMatch(localStarts, startEpochFinal, awaitingRekickoff);
                    if (awaitingRekickoff && initialKickoffLocalReady) {
                        controller.markOnlineInitialKickoffSubmitted();
                    }
                    if (canonicalState != null && canonicalState.length() > 0) {
                        applyAuthoritativePhaseState(canonicalState);
                    } else if (!awaitingRekickoff) {
                        controller.applyAuthoritativePhaseState(
                                localIsPlayerA,
                                state.homeScore,
                                state.awayScore,
                                localIsPlayerA ? state.ballPos : -state.ballPos,
                                localIsPlayerA ? state.yourMomentum : state.oppMomentum,
                                localIsPlayerA ? state.oppMomentum : state.yourMomentum,
                                "player_a".equalsIgnoreCase(turnOwner) ? localIsPlayerA : !localIsPlayerA,
                                turnRemainingFromJoin,
                                matchElapsedFromJoin,
                                controller.isKickoffPending(),
                                controller.isKickoffResponseResolveOnPlayerEnd()
                        );
                    }
                    controller.showBanner("ONLINE MATCH VS " + opponentLabelFinal.toUpperCase(), SystemClock.uptimeMillis(), 1500);
                    onlineLastActionSeq = lastSeqFinal;
                    onlineLastAppliedActionSeq = lastSeqFinal;
                    onlineGameplayReady = true;
                    onlineInitInProgress = false;
                    gameStarted = true;
                    postDelayed(onlinePresenceHeartbeat, 500L);
                    connectOnlineRealtime(false);
                    invalidate();
                });
            } catch (Exception e) {
                final String message = mapOnlineSyncError(e);
                Log.w(TAG, "bootstrapOnlineGameplay failed: " + message, e);
                post(() -> {
                    onlineInitInProgress = false;
                    onlineInitFailed = true;
                    onlineInitError = message;
                    invalidate();
                    postDelayed(() -> {
                        if (menuListener != null) menuListener.onExitToMenu();
                    }, 1800L);
                });
            }
        });
        if (!submitted) {
            onlineInitInProgress = false;
            onlineInitFailed = true;
            onlineInitError = "Online worker unavailable";
            invalidate();
        }
    }

    private int maxSeq(List<SupabaseService.MatchAction> actions) {
        int max = -1;
        if (actions == null) return max;
        for (SupabaseService.MatchAction action : actions) {
            if (action == null) continue;
            if (action.seq > max) max = action.seq;
        }
        return max;
    }

    private static final class KickoffState {
        boolean localReady = false;
        boolean remoteReady = false;
        boolean bothReady = false;
        long startEpochMs = 0L;
    }

    private KickoffState deriveKickoffState(List<SupabaseService.MatchAction> readyActions, String localUserId) {
        KickoffState state = new KickoffState();
        if (readyActions == null || readyActions.isEmpty()) {
            return state;
        }

        List<SupabaseService.MatchAction> sorted = new ArrayList<>();
        for (SupabaseService.MatchAction action : readyActions) {
            if (action == null) continue;
            sorted.add(action);
        }
        Collections.sort(sorted, Comparator.comparingInt(a -> a.seq));

        long fallback = 0L;
        for (SupabaseService.MatchAction action : sorted) {
            if (!isKickoffReadyPayload(action.payload)) continue;
            String actorId = normalize(action.actorUserId);
            if (actorId == null) continue;

            if (localUserId != null && localUserId.equals(actorId)) {
                state.localReady = true;
            } else {
                state.remoteReady = true;
            }

            if (action.createdAtEpochMs > 0L) {
                fallback = Math.max(fallback, action.createdAtEpochMs);
            }
            if (state.localReady && state.remoteReady) {
                state.bothReady = true;
                state.startEpochMs = action.createdAtEpochMs > 0L ? action.createdAtEpochMs : fallback;
                break;
            }
        }

        if (state.bothReady && state.startEpochMs <= 0L) {
            state.startEpochMs = System.currentTimeMillis();
        }
        return state;
    }

    private long deriveMatchStartEpochMs(List<SupabaseService.MatchAction> readyActions) {
        if (readyActions == null || readyActions.isEmpty()) return 0L;

        Set<String> seenActors = new HashSet<>();
        long fallback = 0L;
        for (SupabaseService.MatchAction action : readyActions) {
            if (action == null) continue;
            if (action.createdAtEpochMs > 0L) {
                fallback = Math.max(fallback, action.createdAtEpochMs);
            }
            String actor = normalize(action.actorUserId);
            if (actor == null || actor.isEmpty()) continue;
            if (seenActors.contains(actor)) continue;
            seenActors.add(actor);
            if (seenActors.size() == 2 && action.createdAtEpochMs > 0L) {
                return action.createdAtEpochMs;
            }
        }
        return fallback;
    }

    private boolean choosePlayerAStarts(String matchId) {
        String normalized = normalize(matchId);
        if (normalized == null) return true;
        try {
            UUID uuid = UUID.fromString(normalized);
            return (uuid.getLeastSignificantBits() & 1L) == 0L;
        } catch (Exception ignored) {
        }
        return (normalized.hashCode() & 1) == 0;
    }

    private void drawLoadingOverlay(Canvas canvas) {
        canvas.drawColor(Color.rgb(15, 30, 20));
        float centerX = canvas.getWidth() * 0.5f;
        float centerY = canvas.getHeight() * 0.5f;

        String title;
        String subtitle;
        if (onlineMode && onlineInitInProgress) {
            title = "Syncing online match...";
            subtitle = "Loading game state";
        } else if (onlineMode && onlineInitFailed) {
            title = "Unable to sync online match";
            subtitle = onlineInitError == null || onlineInitError.isEmpty()
                    ? "Returning to menu..."
                    : onlineInitError;
        } else if (assetsLoading) {
            title = "Loading match...";
            subtitle = "Please wait";
        } else if (assetsFailed) {
            title = "Starting with fallback visuals";
            subtitle = "Some art/audio may be unavailable";
        } else {
            title = "Starting match...";
            subtitle = "Preparing game";
        }

        canvas.drawText(title, centerX, centerY, loadingTextPaint);
        canvas.drawText(subtitle, centerX, centerY + (24f * density), loadingSubTextPaint);
    }

    private void clearDragStateIfNeeded() {
        if (dragState.dragging == null
                && dragState.pressedCard == null
                && dragState.pressedBoardCard == null) {
            return;
        }
        TurnEngine.TurnState turnState = controller.getTurnEngine().getTurnState();
        boolean clearForMatchOver = state.matchOver && dragState.dragging != null;
        boolean clearForInactiveTurn = !state.matchOver && turnState != TurnEngine.TurnState.PLAYER;
        if (clearForMatchOver || clearForInactiveTurn) {
            dragState.dragging = null;
            dragState.pressedCard = null;
            dragState.longPressTriggered = false;
            dragState.pressedBoardCard = null;
            dragState.boardLongPressTriggered = false;
            if (dragState.longPressRunnable != null) {
                removeCallbacks(dragState.longPressRunnable);
                dragState.longPressRunnable = null;
            }
            if (dragState.boardLongPressRunnable != null) {
                removeCallbacks(dragState.boardLongPressRunnable);
                dragState.boardLongPressRunnable = null;
            }
        }
    }

    private boolean hasLowStaminaPulse() {
        for (com.roguegamestudio.rugbytcg.PlayerCard pc : state.yourBoard) {
            if (pc.staCurrent == 1) return true;
        }
        for (com.roguegamestudio.rugbytcg.PlayerCard pc : state.oppBoard) {
            if (pc.staCurrent == 1) return true;
        }
        return false;
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private synchronized GuestAuthManager.AuthState ensureOnlineAuth(boolean forceRefresh) throws Exception {
        if (guestAuthManager == null) {
            throw new IllegalStateException("guest_auth_unavailable");
        }
        long nowSec = System.currentTimeMillis() / 1000L;
        GuestAuthManager.AuthState cached = onlineAuthState;
        boolean validCached = cached != null
                && cached.session != null
                && cached.session.expiresAtEpochSeconds > (nowSec + 90L);
        if (!forceRefresh && validCached) {
            return cached;
        }
        GuestAuthManager.AuthState refreshed = guestAuthManager.ensureGuestSession();
        onlineAuthState = refreshed;
        if (refreshed != null && refreshed.session != null) {
            onlineLocalUserId = refreshed.session.userId;
        }
        return refreshed;
    }

    private boolean isAuthError(Exception e) {
        if (e == null || e.getMessage() == null) return false;
        String lower = e.getMessage().toLowerCase();
        return lower.contains("401")
                || lower.contains("jwt")
                || lower.contains("token")
                || lower.contains("not_authenticated");
    }

    private String mapOnlineSyncError(Exception e) {
        if (e == null || e.getMessage() == null) return "Unable to sync online match.";
        String lower = e.getMessage().toLowerCase();
        if (lower.contains("client_upgrade_required")) {
            return "Multiplayer update required.";
        }
        if (isJoinMatchV2AmbiguousSqlError(lower)) {
            return "Multiplayer backend SQL error. Run SQL patch 20260208_multiplayer_v2_join_match_fix.sql.";
        }
        if (isMissingMultiplayerV2RpcError(lower)) {
            return "Missing multiplayer v2 RPCs. Run SQL patch 20260208_multiplayer_v2_authority.sql.";
        }
        if (lower.contains("http 300") || lower.contains("pgrst203") || lower.contains("multiple choices")) {
            return "Duplicate submit_match_action RPC signatures detected. Re-run SQL patch 20260206_online_match_actions.sql.";
        }
        if (lower.contains("match_not_found")) {
            return "Match no longer exists.";
        }
        if (lower.contains("not_match_participant")) {
            return "You are not in this match.";
        }
        if (lower.contains("401")
                || lower.contains("jwt")
                || lower.contains("token")
                || lower.contains("not_authenticated")) {
            return "Multiplayer session expired. Please try again.";
        }
        if (lower.contains("unable to resolve host")
                || lower.contains("failed to connect")
                || lower.contains("timeout")
                || lower.contains("network is unreachable")) {
            return "Unable to reach multiplayer servers.";
        }
        return "Unable to sync online match.";
    }

    private boolean isMissingMultiplayerV2RpcError(String lower) {
        if (lower == null || lower.isEmpty()) return false;
        boolean referencesV2Rpc = lower.contains("submit_match_action_v2")
                || lower.contains("join_match_v2")
                || lower.contains("fetch_actions_since_v2")
                || lower.contains("heartbeat_presence_v2");
        if (!referencesV2Rpc) return false;
        return lower.contains("pgrst202")
                || lower.contains("42883")
                || lower.contains("could not find the function")
                || (lower.contains("function") && lower.contains("does not exist"));
    }

    private boolean isJoinMatchV2AmbiguousSqlError(String lower) {
        if (lower == null || lower.isEmpty()) return false;
        if (!lower.contains("join_match_v2")) return false;
        return lower.contains("42702")
                || lower.contains("column reference \"match_id\" is ambiguous")
                || lower.contains("column reference 'match_id' is ambiguous");
    }

    private String mapActionRejectionBanner(String fallbackText, String reason) {
        String fallback = normalize(fallbackText) != null ? fallbackText : "NOT SYNCED";
        String lower = reason == null ? "" : reason.toLowerCase();
        if (lower.contains("client_upgrade_required")) return "UPDATE REQUIRED";
        if (lower.contains("seq_conflict")) return "RESYNCING...";
        if (lower.contains("not_your_turn")) return "NOT YOUR TURN";
        if (lower.contains("kickoff_required")) return "KICKOFF REQUIRED";
        if (lower.contains("not_awaiting_kickoff")) return "KICKOFF CLOSED";
        if (lower.contains("stale_kickoff_generation")) return "REJOIN KICKOFF";
        if (lower.contains("match_not_active")) return "MATCH ENDED";
        return fallback;
    }

    private void handleActionSubmitRejected(String fallbackText, String reason, boolean clearAckAndKickoff) {
        String normalized = normalize(reason);
        Log.w(TAG, "online action rejected fallback=" + fallbackText
                + " reason=" + normalized
                + " expectedSeq=" + onlineLastActionSeq
                + " appliedSeq=" + onlineLastAppliedActionSeq);
        if (shouldResyncAfterRejectedAction(normalized)) {
            post(this::pollOnlineMatchActions);
        }
        final boolean upgradeRequired = normalized != null && normalized.toLowerCase().contains("client_upgrade_required");
        post(() -> {
            if (clearAckAndKickoff) {
                controller.clearOnlineActionAckPending();
                controller.clearOnlineInitialKickoffWaiting();
            }
            controller.showBanner(
                    mapActionRejectionBanner(fallbackText, reason),
                    SystemClock.uptimeMillis(),
                    1000L
            );
            if (upgradeRequired) {
                scheduleOnlineReturnToMenu(1200L);
            }
        });
    }

    private boolean shouldResyncAfterRejectedAction(String reason) {
        String lower = reason == null ? "" : reason.toLowerCase();
        return lower.contains("seq_conflict")
                || lower.contains("not_your_turn")
                || lower.contains("kickoff_required")
                || lower.contains("stale_kickoff_generation")
                || lower.contains("not_awaiting_kickoff");
    }

    private JSONObject normalizeJoinCanonicalState(JSONObject canonicalState, String joinTurnOwner) {
        if (canonicalState == null || canonicalState.length() == 0) return canonicalState;
        String joinTurn = normalize(joinTurnOwner);
        if (!"player_a".equalsIgnoreCase(joinTurn) && !"player_b".equalsIgnoreCase(joinTurn)) {
            return canonicalState;
        }
        String canonicalTurn = normalize(canonicalState.optString("turn_owner", ""));
        if (canonicalTurn == null || canonicalTurn.equalsIgnoreCase(joinTurn)) {
            return canonicalState;
        }
        Log.w(TAG, "join canonical turn mismatch canonical=" + canonicalTurn + " join=" + joinTurn + " matchId=" + onlineMatchId);
        try {
            JSONObject patched = new JSONObject(canonicalState.toString());
            patched.put("turn_owner", joinTurn.toLowerCase());
            return patched;
        } catch (Exception e) {
            Log.w(TAG, "failed to patch join canonical turn owner", e);
            return canonicalState;
        }
    }

    private void applySubmitResultSyncHints(String actionType, SupabaseService.SubmitActionV2Result result) {
        if (result == null) return;
        onlineLastActionSeq = Math.max(onlineLastActionSeq, Math.max(result.lastSeq, result.seq));
        if (result.kickoffGeneration > 0) {
            onlineKickoffGeneration = Math.max(onlineKickoffGeneration, result.kickoffGeneration);
            post(() -> controller.setOnlineKickoffGeneration(onlineKickoffGeneration));
        }
        if (result.awaitingRekickoff) {
            onlineKickoffStarted = false;
        }
        String normalizedActionType = normalize(actionType);
        boolean localSubmitAction = "play_card".equalsIgnoreCase(normalizedActionType)
                || "end_turn".equalsIgnoreCase(normalizedActionType)
                || "kickoff_ready".equalsIgnoreCase(normalizedActionType)
                || "match_ready".equalsIgnoreCase(normalizedActionType);
        boolean authoritativeAndAccepted = isAuthoritativeClient() && result.accepted && localSubmitAction;
        if (authoritativeAndAccepted) {
            if (PhaseStateSubmissionPolicy.shouldEagerlySubmitAuthoritativePhaseState(normalizedActionType)) {
                final int sourceSeq = result.seq >= 0 ? result.seq : onlineLastActionSeq;
                submitOnlinePhaseState(System.currentTimeMillis(), sourceSeq, "local_play_card_submit");
            }
            return;
        }
        if (result.canonicalState != null && result.canonicalState.length() > 0) {
            applyAuthoritativePhaseState(result.canonicalState);
            return;
        }
        String turnOwner = normalize(result.turnOwner);
        if (!"player_a".equalsIgnoreCase(turnOwner) && !"player_b".equalsIgnoreCase(turnOwner)) {
            return;
        }
        final boolean localTurn = "player_a".equalsIgnoreCase(turnOwner) ? onlineLocalIsPlayerA : !onlineLocalIsPlayerA;
        final long turnRemainingMs = Math.max(0L, result.turnRemainingMs);
        final long matchElapsedMs = Math.max(0L, result.matchElapsedMs);
        post(() -> controller.applyAuthoritativePhaseState(
                onlineLocalIsPlayerA,
                state.homeScore,
                state.awayScore,
                onlineLocalIsPlayerA ? state.ballPos : -state.ballPos,
                onlineLocalIsPlayerA ? state.yourMomentum : state.oppMomentum,
                onlineLocalIsPlayerA ? state.oppMomentum : state.yourMomentum,
                localTurn,
                turnRemainingMs,
                matchElapsedMs,
                controller.isKickoffPending(),
                controller.isKickoffResponseResolveOnPlayerEnd()
        ));
    }

    private boolean executeOnlineSafely(Runnable task) {
        if (task == null) return false;
        ExecutorService executor = onlineExecutor;
        if (executor == null || executor.isShutdown()) return false;
        try {
            executor.execute(task);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void submitOnlinePresenceHeartbeat(boolean appActive) {
        if (!onlineMode || guestAuthManager == null || supabaseService == null || onlineExecutor == null) {
            return;
        }
        if (onlineExecutor.isShutdown()) return;
        executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(false);
                SupabaseService.PresenceHeartbeatResult result = supabaseService.heartbeatPresenceV2(
                        auth.session.accessToken,
                        onlineMatchId,
                        appActive
                );
                if (!result.accepted && isAuthError(new Exception(result.reason))) {
                    auth = ensureOnlineAuth(true);
                    result = supabaseService.heartbeatPresenceV2(
                            auth.session.accessToken,
                            onlineMatchId,
                            appActive
                    );
                }
                if (result.accepted && result.kickoffGeneration > 0) {
                    onlineKickoffGeneration = Math.max(onlineKickoffGeneration, result.kickoffGeneration);
                    post(() -> controller.setOnlineKickoffGeneration(onlineKickoffGeneration));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void submitOnlinePlayCard(CardId cardId) {
        if (!onlineMode || cardId == null || guestAuthManager == null || supabaseService == null || onlineExecutor == null) {
            return;
        }
        if (onlineExecutor.isShutdown()) return;
        executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(false);
                SupabaseService.SubmitActionV2Result result = submitPlayCardOnce(auth, cardId);
                if (!result.accepted && isAuthError(new Exception(result.reason))) {
                    auth = ensureOnlineAuth(true);
                    result = submitPlayCardOnce(auth, cardId);
                }
                applySubmitResultSyncHints("play_card", result);
                if (!result.accepted) {
                    handleActionSubmitRejected("PLAY NOT SYNCED", result.reason, false);
                }
            } catch (Exception e) {
                if (isAuthError(e)) {
                    try {
                        GuestAuthManager.AuthState refreshed = ensureOnlineAuth(true);
                        SupabaseService.SubmitActionV2Result retried = submitPlayCardOnce(refreshed, cardId);
                        applySubmitResultSyncHints("play_card", retried);
                        if (!retried.accepted) {
                            handleActionSubmitRejected("PLAY NOT SYNCED", retried.reason, false);
                        }
                        return;
                    } catch (Exception ignored) {
                    }
                }
                handleActionSubmitRejected("PLAY NOT SYNCED", e.getMessage(), false);
            }
        });
    }

    private void submitOnlineEndTurn() {
        if (!onlineMode || guestAuthManager == null || supabaseService == null || onlineExecutor == null) {
            return;
        }
        if (onlineExecutor.isShutdown()) return;
        executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(false);
                SupabaseService.SubmitActionV2Result result = submitEndTurnOnce(auth);
                if (!result.accepted && isAuthError(new Exception(result.reason))) {
                    auth = ensureOnlineAuth(true);
                    result = submitEndTurnOnce(auth);
                }
                applySubmitResultSyncHints("end_turn", result);
                if (!result.accepted) {
                    handleActionSubmitRejected("TURN NOT SYNCED", result.reason, true);
                }
            } catch (Exception e) {
                if (isAuthError(e)) {
                    try {
                        GuestAuthManager.AuthState refreshed = ensureOnlineAuth(true);
                        SupabaseService.SubmitActionV2Result retried = submitEndTurnOnce(refreshed);
                        applySubmitResultSyncHints("end_turn", retried);
                        if (!retried.accepted) {
                            handleActionSubmitRejected("TURN NOT SYNCED", retried.reason, true);
                        }
                        return;
                    } catch (Exception ignored) {
                    }
                }
                handleActionSubmitRejected("TURN NOT SYNCED", e.getMessage(), true);
            }
        });
    }

    private void submitOnlineKickoffReady() {
        if (!onlineMode || guestAuthManager == null || supabaseService == null || onlineExecutor == null) {
            return;
        }
        if (onlineExecutor.isShutdown()) return;
        executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(false);
                SupabaseService.SubmitActionV2Result result = submitKickoffReadyOnce(auth);
                if (!result.accepted && isAuthError(new Exception(result.reason))) {
                    auth = ensureOnlineAuth(true);
                    result = submitKickoffReadyOnce(auth);
                }
                applySubmitResultSyncHints("kickoff_ready", result);
                if (!result.accepted) {
                    handleActionSubmitRejected("KICKOFF NOT SYNCED", result.reason, true);
                }
            } catch (Exception e) {
                if (isAuthError(e)) {
                    try {
                        GuestAuthManager.AuthState refreshed = ensureOnlineAuth(true);
                        SupabaseService.SubmitActionV2Result retried = submitKickoffReadyOnce(refreshed);
                        applySubmitResultSyncHints("kickoff_ready", retried);
                        if (!retried.accepted) {
                            handleActionSubmitRejected("KICKOFF NOT SYNCED", retried.reason, true);
                        }
                        return;
                    } catch (Exception ignored) {
                    }
                }
                handleActionSubmitRejected("KICKOFF NOT SYNCED", e.getMessage(), true);
            }
        });
    }

    private SupabaseService.SubmitActionV2Result submitPlayCardOnce(GuestAuthManager.AuthState auth, CardId cardId)
            throws Exception {
        if (auth == null || auth.session == null) throw new IllegalStateException("missing_auth");
        JSONObject payload = new JSONObject();
        payload.put("card_id", cardId.name());
        return supabaseService.submitMatchActionV2(
                auth.session.accessToken,
                onlineMatchId,
                "play_card",
                payload,
                onlineLastActionSeq
        );
    }

    private SupabaseService.SubmitActionV2Result submitEndTurnOnce(GuestAuthManager.AuthState auth)
            throws Exception {
        if (auth == null || auth.session == null) throw new IllegalStateException("missing_auth");
        return supabaseService.submitMatchActionV2(
                auth.session.accessToken,
                onlineMatchId,
                "end_turn",
                new JSONObject(),
                onlineLastActionSeq
        );
    }

    private SupabaseService.SubmitActionV2Result submitKickoffReadyOnce(GuestAuthManager.AuthState auth)
            throws Exception {
        if (auth == null || auth.session == null) throw new IllegalStateException("missing_auth");
        JSONObject payload = new JSONObject();
        int generation = Math.max(0, controller.getOnlineKickoffGeneration());
        payload.put("generation", generation);
        return supabaseService.submitMatchActionV2(
                auth.session.accessToken,
                onlineMatchId,
                "kickoff_ready",
                payload,
                onlineLastActionSeq
        );
    }

    private void pollOnlineMatchActions() {
        if (!onlineMode || !onlineGameplayReady) return;
        if (guestAuthManager == null || supabaseService == null || onlineExecutor == null) return;
        if (onlineExecutor.isShutdown()) return;
        if (onlineActionPollInFlight) return;
        onlineActionPollInFlight = true;

        boolean submitted = executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(false);
                drainOnlineActionPages(auth, onlineLastAppliedActionSeq);
            } catch (Exception e) {
                if (isAuthError(e)) {
                    try {
                        GuestAuthManager.AuthState refreshed = ensureOnlineAuth(true);
                        drainOnlineActionPages(refreshed, onlineLastAppliedActionSeq);
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                boolean repoll = onlineActionRepollRequested || hasBufferedOnlineActionGap();
                onlineActionRepollRequested = false;
                onlineActionPollInFlight = false;
                if (repoll) {
                    post(this::pollOnlineMatchActions);
                }
            }
        });
        if (!submitted) {
            onlineActionPollInFlight = false;
        }
    }

    private void drainOnlineActionPages(GuestAuthManager.AuthState auth, int initialAfterSeq) throws Exception {
        if (auth == null || auth.session == null) throw new IllegalStateException("missing_auth");
        int cursor = Math.max(-1, initialAfterSeq);
        int pageCount = 0;
        boolean hasMore;
        do {
            SupabaseService.MatchActionPage page = supabaseService.fetchMatchActionsSinceV2(
                    auth.session.accessToken,
                    onlineMatchId,
                    cursor,
                    ONLINE_ACTION_PAGE_SIZE
            );
            List<SupabaseService.MatchAction> actions = page != null ? page.actions : null;
            ingestOnlineActions(auth.session.userId, actions);
            cursor = Math.max(cursor, maxSeq(actions));
            hasMore = page != null && page.hasMore;
            pageCount++;
        } while (hasMore && pageCount < ONLINE_ACTION_MAX_PAGES_PER_POLL);

        if (hasMore) {
            post(this::pollOnlineMatchActions);
        }
    }

    private void ingestOnlineActions(String localUserId, List<SupabaseService.MatchAction> actions) {
        if (actions == null || actions.isEmpty()) return;
        List<SupabaseService.MatchAction> sorted = new ArrayList<>();
        for (SupabaseService.MatchAction action : actions) {
            if (action == null || action.seq < 0) continue;
            sorted.add(action);
        }
        if (sorted.isEmpty()) return;
        Collections.sort(sorted, Comparator.comparingInt(a -> a.seq));
        for (SupabaseService.MatchAction action : sorted) {
            enqueueOnlineAction(localUserId, action);
        }
    }

    private void enqueueOnlineAction(String localUserId, SupabaseService.MatchAction action) {
        if (action == null) return;
        if (action.seq <= onlineLastAppliedActionSeq) return;
        if (onlineActionBuffer.containsKey(action.seq)) return;
        onlineActionBuffer.put(action.seq, action);
        drainBufferedOnlineActions(localUserId);
        requestOnlineActionGapRecoveryIfNeeded();
    }

    private void drainBufferedOnlineActions(String localUserId) {
        while (true) {
            int expected = onlineLastAppliedActionSeq + 1;
            SupabaseService.MatchAction next = onlineActionBuffer.get(expected);
            if (next == null) break;
            onlineActionBuffer.remove(expected);
            onlineLastAppliedActionSeq = next.seq;
            onlineLastActionSeq = Math.max(onlineLastActionSeq, onlineLastAppliedActionSeq);
            Log.d(TAG, "ingest action seq=" + next.seq + " type=" + next.actionType + " actor=" + next.actorUserId);
            dispatchOnlineMatchAction(localUserId, next);
        }
    }

    private boolean hasBufferedOnlineActionGap() {
        return !onlineActionBuffer.isEmpty()
                && onlineActionBuffer.firstKey() > (onlineLastAppliedActionSeq + 1);
    }

    private void requestOnlineActionGapRecoveryIfNeeded() {
        if (!hasBufferedOnlineActionGap()) return;
        if (!onlineActionPollInFlight) {
            pollOnlineMatchActions();
            return;
        }
        onlineActionRepollRequested = true;
        if (!isAuthoritativeClient()) {
            submitOnlineResyncRequest();
        }
    }

    private void dispatchOnlineMatchAction(String localUserId, SupabaseService.MatchAction action) {
        if (action == null) return;
        String actionType = normalize(action.actionType);
        if (actionType == null) return;
        actionType = actionType.toLowerCase();

        String resolvedLocalUserId = localUserId != null ? localUserId : onlineLocalUserId;
        boolean fromSelf = resolvedLocalUserId != null && resolvedLocalUserId.equals(action.actorUserId);
        if ("kickoff_ready".equals(actionType)) {
            handleKickoffReadyAction(fromSelf, action);
            return;
        }

        if (fromSelf) {
            if ("play_card".equals(actionType) && isAuthoritativeClient()) {
                post(() -> submitOnlinePhaseState(action.createdAtEpochMs, action.seq, "local_play_card_replay"));
            }
            if ("end_turn".equals(actionType)) {
                post(() -> {
                    controller.confirmLocalEndTurnAtServerTime(action.createdAtEpochMs);
                    if (isAuthoritativeClient()) {
                        submitOnlinePhaseState(action.createdAtEpochMs, action.seq, "local_end_turn_replay");
                    }
                });
            }
            if ("kickoff_ready".equals(actionType)) {
                post(controller::clearOnlineActionAckPending);
            }
            return;
        }

        if ("play_card".equals(actionType)) {
            CardId cardId = parseCardId(action.payload);
            if (cardId == null) return;
            post(() -> {
                controller.applyRemotePlayCard(cardId, action.createdAtEpochMs);
                if (isAuthoritativeClient()) {
                    submitOnlinePhaseState(action.createdAtEpochMs, action.seq, "remote_play_card_replay");
                }
            });
            return;
        }
        if ("end_turn".equals(actionType)) {
            post(() -> {
                controller.applyRemoteEndTurn(action.createdAtEpochMs);
                if (isAuthoritativeClient()) {
                    submitOnlinePhaseState(action.createdAtEpochMs, action.seq, "remote_end_turn_replay");
                }
            });
            return;
        }
        if ("phase_state".equals(actionType)) {
            if (isActionFromAuthoritative(action)) {
                applyAuthoritativePhaseState(action.payload);
            }
            return;
        }
        if ("resync_request".equals(actionType)) {
            if (isAuthoritativeClient()) {
                post(() -> submitOnlinePhaseState(action.createdAtEpochMs, action.seq, "resync_request"));
            }
        }
    }

    private void handleKickoffReadyAction(boolean fromSelf, SupabaseService.MatchAction action) {
        if (!onlineMode || !onlineGameplayReady || action == null) return;
        if (!controller.isOnlineInitialKickoffPending() || onlineKickoffStarted) return;
        int generation = action.payload != null ? action.payload.optInt("generation", -1) : -1;
        if (generation > 0) {
            if (onlineKickoffGeneration > 0 && generation != onlineKickoffGeneration) {
                return;
            }
            onlineKickoffGeneration = generation;
            post(() -> controller.setOnlineKickoffGeneration(onlineKickoffGeneration));
        }

        if (fromSelf) {
            onlineKickoffLocalReady = true;
            post(controller::clearOnlineActionAckPending);
        } else {
            onlineKickoffRemoteReady = true;
        }
        if (!onlineKickoffLocalReady || !onlineKickoffRemoteReady) return;

        onlineKickoffStarted = true;
        long kickoffStartEpochMs = action.createdAtEpochMs > 0L
                ? action.createdAtEpochMs
                : System.currentTimeMillis();
        post(() -> {
            controller.completeOnlineInitialKickoff(kickoffStartEpochMs);
            if (isAuthoritativeClient()) {
                submitOnlinePhaseState(action.createdAtEpochMs, action.seq, "kickoff_completion");
            }
        });
    }

    private CardId parseCardId(JSONObject payload) {
        if (payload == null) return null;
        String raw = normalize(payload.optString("card_id", ""));
        if (raw == null) return null;
        try {
            return CardId.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private boolean isKickoffReadyPayload(JSONObject payload) {
        if (payload == null) return false;
        String stage = normalize(payload.optString("stage", ""));
        if (stage != null && "kickoff".equalsIgnoreCase(stage)) return true;
        return payload.optInt("generation", -1) > 0;
    }

    private void connectOnlineRealtime(boolean forceRefresh) {
        if (!isAttachedToWindow()) return;
        if (!onlineMode || !onlineGameplayReady) return;
        if (guestAuthManager == null || supabaseService == null || onlineExecutor == null) return;
        if (onlineExecutor.isShutdown()) return;
        if (onlineRealtimeConnected || onlineRealtimeClient != null) return;

        executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(forceRefresh);
                setupRealtimeClient(auth.session.accessToken);
            } catch (Exception e) {
                scheduleOnlineRealtimeReconnect(isAuthError(e));
            }
        });
    }

    private void setupRealtimeClient(String accessToken) {
        shutdownOnlineRealtimeClient();
        onlineRealtimeClient = new SupabaseRealtimeClient(
                BuildConfig.SUPABASE_URL,
                BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                accessToken,
                onlineMatchId,
                new SupabaseRealtimeClient.Listener() {
                    @Override
                    public void onConnected() {
                        runOnlineTask(() -> {
                            onlineRealtimeConnected = true;
                            onlineRealtimeReconnectDelayMs = ONLINE_REALTIME_RECONNECT_MIN_MS;
                            pollOnlineMatchActions();
                            if (!isAuthoritativeClient()) {
                                submitOnlineResyncRequest();
                            }
                        });
                    }

                    @Override
                    public void onDisconnected() {
                        runOnlineTask(() -> {
                            shutdownOnlineRealtimeClient();
                            scheduleOnlineRealtimeReconnect(false);
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnlineTask(() -> {
                            boolean authIssue = message != null && (
                                    message.toLowerCase().contains("jwt")
                                            || message.toLowerCase().contains("token")
                                            || message.toLowerCase().contains("401")
                            );
                            shutdownOnlineRealtimeClient();
                            scheduleOnlineRealtimeReconnect(authIssue);
                        });
                    }

                    @Override
                    public void onMatchAction(SupabaseService.MatchAction action) {
                        runOnlineTask(() -> {
                            if (!onlineGameplayReady || onlineMatchEndedHandled || onlineForfeitHandled) return;
                            String localId = onlineLocalUserId;
                            if (localId == null) {
                                GuestAuthManager.AuthState auth = onlineAuthState;
                                if (auth != null && auth.session != null) {
                                    localId = auth.session.userId;
                                }
                            }
                            if (localId == null) {
                                try {
                                    GuestAuthManager.AuthState auth = ensureOnlineAuth(false);
                                    if (auth != null && auth.session != null) {
                                        localId = auth.session.userId;
                                    }
                                } catch (Exception ignored) {
                                }
                            }
                            enqueueOnlineAction(localId, action);
                        });
                    }
                }
        );
        onlineRealtimeClient.connect();
    }

    private void shutdownOnlineRealtimeClient() {
        SupabaseRealtimeClient client = onlineRealtimeClient;
        onlineRealtimeClient = null;
        if (client != null) {
            client.disconnect();
        }
        onlineRealtimeConnected = false;
    }

    private void scheduleOnlineRealtimeReconnect(boolean forceRefresh) {
        if (!onlineMode || !onlineGameplayReady || onlineForfeitHandled || onlineMatchEndedHandled) return;
        onlineRealtimeForceRefresh = onlineRealtimeForceRefresh || forceRefresh;
        if (onlineRealtimeReconnectScheduled) return;

        onlineRealtimeReconnectScheduled = true;
        long delay = onlineRealtimeReconnectDelayMs;
        onlineRealtimeReconnectDelayMs = Math.min(
                ONLINE_REALTIME_RECONNECT_MAX_MS,
                onlineRealtimeReconnectDelayMs * 2L
        );
        mainHandler.postDelayed(onlineRealtimeReconnect, delay);
    }

    private void runOnlineTask(Runnable task) {
        if (task == null) return;
        if (!isAttachedToWindow()) return;
        ExecutorService executor = onlineExecutor;
        if (executor == null || executor.isShutdown()) return;
        try {
            executor.execute(task);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private boolean isAuthoritativeLocalPlayer() {
        return onlineLocalUserId != null
                && onlineAuthoritativeUserId != null
                && onlineLocalUserId.equals(onlineAuthoritativeUserId);
    }

    // Multiplayer protocol currently treats player_a as authoritative.
    private boolean isAuthoritativeClient() {
        if (onlineLocalIsPlayerA) return true;
        return isAuthoritativeLocalPlayer();
    }

    private boolean isActionFromAuthoritative(SupabaseService.MatchAction action) {
        if (action == null) return false;
        return onlineAuthoritativeUserId != null && onlineAuthoritativeUserId.equals(action.actorUserId);
    }

    private void submitOnlinePhaseState(long sourceEpochMs, int sourceSeq) {
        submitOnlinePhaseState(sourceEpochMs, sourceSeq, "unspecified");
    }

    private void submitOnlinePhaseState(long sourceEpochMs, int sourceSeq, String submissionSource) {
        if (!onlineMode || !onlineGameplayReady || !isAuthoritativeClient()) return;
        if (guestAuthManager == null || supabaseService == null || onlineExecutor == null) return;
        if (onlineExecutor.isShutdown()) return;
        executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(false);
                JSONObject payload = buildPhaseStatePayload(sourceEpochMs, sourceSeq);
                logPhaseStateSubmit(submissionSource, payload, sourceSeq);
                SupabaseService.SubmitActionV2Result result = submitPhaseStateWithRetry(auth, payload);
                applyPhaseStateResultSyncHints(result, auth);
                String reason = normalize(result.reason);
                if (result.accepted) {
                    Log.d(TAG, "phase_state accepted seq=" + result.seq + " lastSeq=" + result.lastSeq);
                } else {
                    Log.w(TAG, "phase_state rejected reason=" + result.reason + " lastSeq=" + result.lastSeq);
                    if (shouldResyncAfterRejectedAction(reason)) {
                        post(this::pollOnlineMatchActions);
                    }
                }
            } catch (Exception e) {
                if (isAuthError(e)) {
                    try {
                        GuestAuthManager.AuthState refreshed = ensureOnlineAuth(true);
                        JSONObject payload = buildPhaseStatePayload(sourceEpochMs, sourceSeq);
                        logPhaseStateSubmit(submissionSource, payload, sourceSeq);
                        SupabaseService.SubmitActionV2Result result = submitPhaseStateWithRetry(refreshed, payload);
                        applyPhaseStateResultSyncHints(result, refreshed);
                        String reason = normalize(result.reason);
                        if (result.accepted) {
                            Log.d(TAG, "phase_state accepted(after refresh) seq=" + result.seq + " lastSeq=" + result.lastSeq);
                        } else {
                            Log.w(TAG, "phase_state rejected(after refresh) reason=" + result.reason + " lastSeq=" + result.lastSeq);
                            if (shouldResyncAfterRejectedAction(reason)) {
                                post(this::pollOnlineMatchActions);
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private void logPhaseStateSubmit(String submissionSource, JSONObject payload, int sourceSeq) {
        String source = normalize(submissionSource);
        if (source == null) source = "unspecified";
        String turnOwner = payload == null ? "" : normalize(payload.optString("turn_owner", ""));
        Log.d(
                TAG,
                "phase_state submit source=" + source
                        + " turnOwner=" + (turnOwner == null ? "" : turnOwner)
                        + " sourceSeq=" + sourceSeq
                        + " lastSeq=" + onlineLastActionSeq
        );
    }

    private void applyPhaseStateResultSyncHints(SupabaseService.SubmitActionV2Result result,
                                                GuestAuthManager.AuthState auth) {
        if (result == null) return;
        onlineLastActionSeq = Math.max(onlineLastActionSeq, Math.max(result.lastSeq, result.seq));
        if (result.accepted && result.seq == (onlineLastAppliedActionSeq + 1)) {
            onlineLastAppliedActionSeq = result.seq;
            drainBufferedOnlineActions(resolveLocalUserId(auth));
        }
        requestOnlineActionGapRecoveryIfNeeded();
    }

    private String resolveLocalUserId(GuestAuthManager.AuthState auth) {
        if (onlineLocalUserId != null && !onlineLocalUserId.trim().isEmpty()) {
            return onlineLocalUserId;
        }
        if (auth != null && auth.session != null) {
            return auth.session.userId;
        }
        return null;
    }

    private SupabaseService.SubmitActionV2Result submitPhaseStateWithRetry(GuestAuthManager.AuthState auth,
                                                                            JSONObject payload) throws Exception {
        SupabaseService.SubmitActionV2Result result = submitMatchActionWithRetry(
                auth,
                "phase_state",
                payload,
                onlineLastActionSeq
        );
        if (!result.accepted) {
            String reason = normalize(result.reason);
            if (reason != null && reason.toLowerCase().contains("seq_conflict")) {
                result = submitMatchActionWithRetry(auth, "phase_state", payload, null);
            }
        }
        return result;
    }

    private JSONObject buildPhaseStatePayload(long sourceEpochMs, int sourceSeq) throws Exception {
        JSONObject payload = new JSONObject();
        int canonicalBallPos = onlineLocalIsPlayerA ? state.ballPos : -state.ballPos;
        int scoreA = onlineLocalIsPlayerA ? state.homeScore : state.awayScore;
        int scoreB = onlineLocalIsPlayerA ? state.awayScore : state.homeScore;
        int momentumA = onlineLocalIsPlayerA ? state.yourMomentum : state.oppMomentum;
        int momentumB = onlineLocalIsPlayerA ? state.oppMomentum : state.yourMomentum;
        boolean localTurn = controller.getTurnEngine().getTurnState() == TurnEngine.TurnState.PLAYER;
        String turnOwner = localTurn
                ? (onlineLocalIsPlayerA ? "player_a" : "player_b")
                : (onlineLocalIsPlayerA ? "player_b" : "player_a");
        payload.put("v", 1);
        payload.put("source_seq", sourceSeq);
        payload.put("source_epoch_ms", sourceEpochMs);
        payload.put("score_a", scoreA);
        payload.put("score_b", scoreB);
        payload.put("ball_canonical", canonicalBallPos);
        payload.put("momentum_a", momentumA);
        payload.put("momentum_b", momentumB);
        payload.put("turn_owner", turnOwner);
        if (controller.getTurnEngine().isTurnTimeoutEnabled()) {
            payload.put("turn_remaining_ms", Math.max(0L, controller.getTurnEngine().getTurnRemainingMs()));
        }
        payload.put("match_elapsed_ms", Math.max(0L, controller.getMatchEngine().getMatchElapsedMs(state)));
        payload.put("kickoff_pending", controller.isKickoffPending());
        payload.put("kickoff_response_pending", controller.isKickoffResponseResolveOnPlayerEnd());
        payload.put("hand_a", buildHandArray(onlineLocalIsPlayerA ? state.yourHand : state.oppHand));
        payload.put("hand_b", buildHandArray(onlineLocalIsPlayerA ? state.oppHand : state.yourHand));
        payload.put("board_a", buildBoardArray(onlineLocalIsPlayerA ? state.yourBoard : state.oppBoard));
        payload.put("board_b", buildBoardArray(onlineLocalIsPlayerA ? state.oppBoard : state.yourBoard));
        payload.put("temp_pwr_a", onlineLocalIsPlayerA ? state.tempPwrBonusYou : state.tempPwrBonusOpp);
        payload.put("temp_skl_a", onlineLocalIsPlayerA ? state.tempSklBonusYou : state.tempSklBonusOpp);
        payload.put("temp_pwr_b", onlineLocalIsPlayerA ? state.tempPwrBonusOpp : state.tempPwrBonusYou);
        payload.put("temp_skl_b", onlineLocalIsPlayerA ? state.tempSklBonusOpp : state.tempSklBonusYou);
        payload.put("cards_played_a", onlineLocalIsPlayerA ? state.yourCardsPlayedThisPhase : state.oppCardsPlayedThisPhase);
        payload.put("cards_played_b", onlineLocalIsPlayerA ? state.oppCardsPlayedThisPhase : state.yourCardsPlayedThisPhase);
        payload.put("next_bonus_a", onlineLocalIsPlayerA ? state.nextTurnMomentumBonusYou : state.nextTurnMomentumBonusOpp);
        payload.put("next_bonus_b", onlineLocalIsPlayerA ? state.nextTurnMomentumBonusOpp : state.nextTurnMomentumBonusYou);
        payload.put("tight_play_a", onlineLocalIsPlayerA ? state.tightPlayYou : state.tightPlayOpp);
        payload.put("tight_play_b", onlineLocalIsPlayerA ? state.tightPlayOpp : state.tightPlayYou);
        payload.put("drive_used_a", onlineLocalIsPlayerA ? state.driveUsedThisTurnYou : state.driveUsedThisTurnOpp);
        payload.put("drive_used_b", onlineLocalIsPlayerA ? state.driveUsedThisTurnOpp : state.driveUsedThisTurnYou);
        payload.put("active_tactic_a", cardIdName(cardIdOf(onlineLocalIsPlayerA ? state.activeTacticYou : state.activeTacticOpp)));
        payload.put("active_tactic_b", cardIdName(cardIdOf(onlineLocalIsPlayerA ? state.activeTacticOpp : state.activeTacticYou)));
        return payload;
    }

    private void submitOnlineResyncRequest() {
        if (!onlineMode || !onlineGameplayReady || isAuthoritativeClient()) return;
        if (guestAuthManager == null || supabaseService == null || onlineExecutor == null) return;
        if (onlineExecutor.isShutdown()) return;
        long now = SystemClock.elapsedRealtime();
        if ((now - onlineLastResyncRequestElapsedMs) < ONLINE_RESYNC_REQUEST_MIN_GAP_MS) return;
        onlineLastResyncRequestElapsedMs = now;

        executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(false);
                SupabaseService.SubmitActionV2Result result = submitMatchActionWithRetry(auth, "resync_request", new JSONObject());
                if (!result.accepted && normalize(result.reason) != null
                        && result.reason.toLowerCase().contains("seq_conflict")) {
                    post(this::pollOnlineMatchActions);
                }
            } catch (Exception e) {
                if (isAuthError(e)) {
                    try {
                        GuestAuthManager.AuthState refreshed = ensureOnlineAuth(true);
                        SupabaseService.SubmitActionV2Result result = submitMatchActionWithRetry(refreshed, "resync_request", new JSONObject());
                        if (!result.accepted && normalize(result.reason) != null
                                && result.reason.toLowerCase().contains("seq_conflict")) {
                            post(this::pollOnlineMatchActions);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }

    private SupabaseService.SubmitActionV2Result submitMatchActionWithRetry(GuestAuthManager.AuthState auth,
                                                                             String actionType,
                                                                             JSONObject payload) throws Exception {
        return submitMatchActionWithRetry(auth, actionType, payload, onlineLastActionSeq);
    }

    private SupabaseService.SubmitActionV2Result submitMatchActionWithRetry(GuestAuthManager.AuthState auth,
                                                                             String actionType,
                                                                             JSONObject payload,
                                                                             Integer expectedSeq) throws Exception {
        if (auth == null || auth.session == null) throw new IllegalStateException("missing_auth");
        SupabaseService.SubmitActionV2Result result = supabaseService.submitMatchActionV2(
                auth.session.accessToken,
                onlineMatchId,
                actionType,
                payload,
                expectedSeq
        );
        if (!result.accepted && isAuthError(new Exception(result.reason))) {
            GuestAuthManager.AuthState refreshed = ensureOnlineAuth(true);
            result = supabaseService.submitMatchActionV2(
                    refreshed.session.accessToken,
                    onlineMatchId,
                    actionType,
                    payload,
                    expectedSeq
            );
        }
        return result;
    }

    private void applyAuthoritativePhaseState(JSONObject payload) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyAuthoritativePhaseStateOnMainThread(payload);
            return;
        }
        post(() -> applyAuthoritativePhaseStateOnMainThread(payload));
    }

    private void applyAuthoritativePhaseStateOnMainThread(JSONObject payload) {
        if (payload == null || payload.length() == 0) return;
        String turnOwner = normalize(payload.optString("turn_owner", ""));
        if (turnOwner == null) return;

        int scoreA = payload.optInt("score_a", Integer.MIN_VALUE);
        int scoreB = payload.optInt("score_b", Integer.MIN_VALUE);
        int ballCanonical = payload.optInt("ball_canonical", Integer.MIN_VALUE);
        int momentumA = payload.optInt("momentum_a", Integer.MIN_VALUE);
        int momentumB = payload.optInt("momentum_b", Integer.MIN_VALUE);
        long turnRemainingMs = payload.optLong("turn_remaining_ms", -1L);
        long matchElapsedMs = payload.optLong("match_elapsed_ms", -1L);
        if (scoreA == Integer.MIN_VALUE
                || scoreB == Integer.MIN_VALUE
                || ballCanonical == Integer.MIN_VALUE
                || momentumA == Integer.MIN_VALUE
                || momentumB == Integer.MIN_VALUE
                || matchElapsedMs < 0L) {
            return;
        }
        boolean kickoffPendingState = payload.optBoolean("kickoff_pending", false);
        boolean kickoffResponsePendingState = payload.optBoolean("kickoff_response_pending", false);
        boolean localTurn;
        if ("player_a".equalsIgnoreCase(turnOwner)) {
            localTurn = onlineLocalIsPlayerA;
        } else if ("player_b".equalsIgnoreCase(turnOwner)) {
            localTurn = !onlineLocalIsPlayerA;
        } else {
            return;
        }
        List<Card> handA = parseHandArray(payload.optJSONArray("hand_a"));
        List<Card> handB = parseHandArray(payload.optJSONArray("hand_b"));
        List<PlayerCard> boardA = parseBoardArray(payload.optJSONArray("board_a"));
        List<PlayerCard> boardB = parseBoardArray(payload.optJSONArray("board_b"));
        int tempPwrA = payload.optInt("temp_pwr_a", Integer.MIN_VALUE);
        int tempSklA = payload.optInt("temp_skl_a", Integer.MIN_VALUE);
        int tempPwrB = payload.optInt("temp_pwr_b", Integer.MIN_VALUE);
        int tempSklB = payload.optInt("temp_skl_b", Integer.MIN_VALUE);
        int cardsPlayedA = payload.optInt("cards_played_a", Integer.MIN_VALUE);
        int cardsPlayedB = payload.optInt("cards_played_b", Integer.MIN_VALUE);
        int nextBonusA = payload.optInt("next_bonus_a", Integer.MIN_VALUE);
        int nextBonusB = payload.optInt("next_bonus_b", Integer.MIN_VALUE);
        boolean tightPlayA = payload.optBoolean("tight_play_a", false);
        boolean tightPlayB = payload.optBoolean("tight_play_b", false);
        boolean driveUsedA = payload.optBoolean("drive_used_a", false);
        boolean driveUsedB = payload.optBoolean("drive_used_b", false);
        CardId activeTacticA = parseOptionalCardId(payload.optString("active_tactic_a", ""));
        CardId activeTacticB = parseOptionalCardId(payload.optString("active_tactic_b", ""));
        applyAuthoritativeHandsAndBoards(handA, handB, boardA, boardB);
        applyAuthoritativePhaseTemps(
                tempPwrA, tempSklA, tempPwrB, tempSklB,
                cardsPlayedA, cardsPlayedB,
                nextBonusA, nextBonusB,
                tightPlayA, tightPlayB,
                driveUsedA, driveUsedB,
                activeTacticA, activeTacticB
        );
        controller.applyAuthoritativePhaseState(
                onlineLocalIsPlayerA,
                scoreA,
                scoreB,
                ballCanonical,
                momentumA,
                momentumB,
                localTurn,
                turnRemainingMs,
                matchElapsedMs,
                kickoffPendingState,
                kickoffResponsePendingState
        );
    }

    private String resolveLocalOnlineLabel(GuestAuthManager.AuthState auth) {
        if (auth == null || auth.session == null) return "You";
        try {
            SupabaseProfile profile = supabaseService.fetchProfile(auth.session.accessToken, auth.session.userId);
            String publicId = normalize(profile != null ? profile.publicId : null);
            if (publicId != null) return publicId;
            String username = normalize(profile != null ? profile.username : null);
            if (username != null) return username;
        } catch (Exception ignored) {
        }
        String fallbackUserId = normalize(auth.session.userId);
        if (fallbackUserId == null) return "You";
        if (fallbackUserId.length() <= 8) return fallbackUserId;
        return fallbackUserId.substring(0, 8);
    }

    private org.json.JSONArray buildHandArray(List<Card> cards) {
        org.json.JSONArray out = new org.json.JSONArray();
        if (cards == null) return out;
        for (Card c : cards) {
            if (c == null || c.id == null) continue;
            out.put(c.id.name());
        }
        return out;
    }

    private org.json.JSONArray buildBoardArray(List<PlayerCard> cards) throws Exception {
        org.json.JSONArray out = new org.json.JSONArray();
        if (cards == null) return out;
        for (PlayerCard c : cards) {
            if (c == null || c.id == null) continue;
            JSONObject row = new JSONObject();
            row.put("id", c.id.name());
            row.put("sta", c.staCurrent);
            out.put(row);
        }
        return out;
    }

    private List<Card> parseHandArray(org.json.JSONArray arr) {
        if (arr == null) return null;
        List<Card> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            String raw = arr.optString(i, "");
            CardId id = parseOptionalCardId(raw);
            if (id == null) continue;
            Card copy = copyCardFromStarterDeck(id);
            if (copy != null) out.add(copy);
        }
        return out;
    }

    private List<PlayerCard> parseBoardArray(org.json.JSONArray arr) {
        if (arr == null) return null;
        List<PlayerCard> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) continue;
            CardId id = parseOptionalCardId(row.optString("id", ""));
            if (id == null) continue;
            Card base = copyCardFromStarterDeck(id);
            if (!(base instanceof PlayerCard)) continue;
            PlayerCard pc = (PlayerCard) base;
            int sta = row.optInt("sta", pc.staCurrent);
            pc.staCurrent = Math.max(0, Math.min(pc.staMax, sta));
            out.add(pc);
        }
        return out;
    }

    private void applyAuthoritativeHandsAndBoards(List<Card> handA,
                                                  List<Card> handB,
                                                  List<PlayerCard> boardA,
                                                  List<PlayerCard> boardB) {
        if (handA != null && handB != null) {
            state.yourHand.clear();
            state.oppHand.clear();
            if (onlineLocalIsPlayerA) {
                state.yourHand.addAll(handA);
                state.oppHand.addAll(handB);
            } else {
                state.yourHand.addAll(handB);
                state.oppHand.addAll(handA);
            }
        }
        if (boardA != null && boardB != null) {
            state.yourBoard.clear();
            state.oppBoard.clear();
            if (onlineLocalIsPlayerA) {
                state.yourBoard.addAll(boardA);
                state.oppBoard.addAll(boardB);
            } else {
                state.yourBoard.addAll(boardB);
                state.oppBoard.addAll(boardA);
            }
        }
    }

    private void applyAuthoritativePhaseTemps(int tempPwrA,
                                              int tempSklA,
                                              int tempPwrB,
                                              int tempSklB,
                                              int cardsPlayedA,
                                              int cardsPlayedB,
                                              int nextBonusA,
                                              int nextBonusB,
                                              boolean tightPlayA,
                                              boolean tightPlayB,
                                              boolean driveUsedA,
                                              boolean driveUsedB,
                                              CardId activeTacticA,
                                              CardId activeTacticB) {
        if (tempPwrA != Integer.MIN_VALUE && tempSklA != Integer.MIN_VALUE
                && tempPwrB != Integer.MIN_VALUE && tempSklB != Integer.MIN_VALUE) {
            state.tempPwrBonusYou = onlineLocalIsPlayerA ? tempPwrA : tempPwrB;
            state.tempSklBonusYou = onlineLocalIsPlayerA ? tempSklA : tempSklB;
            state.tempPwrBonusOpp = onlineLocalIsPlayerA ? tempPwrB : tempPwrA;
            state.tempSklBonusOpp = onlineLocalIsPlayerA ? tempSklB : tempSklA;
        }
        if (cardsPlayedA != Integer.MIN_VALUE && cardsPlayedB != Integer.MIN_VALUE) {
            state.yourCardsPlayedThisPhase = onlineLocalIsPlayerA ? cardsPlayedA : cardsPlayedB;
            state.oppCardsPlayedThisPhase = onlineLocalIsPlayerA ? cardsPlayedB : cardsPlayedA;
        }
        if (nextBonusA != Integer.MIN_VALUE && nextBonusB != Integer.MIN_VALUE) {
            state.nextTurnMomentumBonusYou = onlineLocalIsPlayerA ? nextBonusA : nextBonusB;
            state.nextTurnMomentumBonusOpp = onlineLocalIsPlayerA ? nextBonusB : nextBonusA;
        }
        state.tightPlayYou = onlineLocalIsPlayerA ? tightPlayA : tightPlayB;
        state.tightPlayOpp = onlineLocalIsPlayerA ? tightPlayB : tightPlayA;
        state.driveUsedThisTurnYou = onlineLocalIsPlayerA ? driveUsedA : driveUsedB;
        state.driveUsedThisTurnOpp = onlineLocalIsPlayerA ? driveUsedB : driveUsedA;
        state.activeTacticYou = toTacticCard(onlineLocalIsPlayerA ? activeTacticA : activeTacticB);
        state.activeTacticOpp = toTacticCard(onlineLocalIsPlayerA ? activeTacticB : activeTacticA);
    }

    private String cardIdName(CardId id) {
        return id == null ? "" : id.name();
    }

    private CardId cardIdOf(Card card) {
        return card == null ? null : card.id;
    }

    private CardId parseOptionalCardId(String raw) {
        String normalized = normalize(raw);
        if (normalized == null) return null;
        try {
            return CardId.valueOf(normalized.toUpperCase());
        } catch (Exception ignored) {
            return null;
        }
    }

    private Card copyCardFromStarterDeck(CardId id) {
        if (id == null) return null;
        for (Card c : StarterDeck.build()) {
            if (c == null || c.id != id) continue;
            return copyCard(c);
        }
        return null;
    }

    private Card copyCard(Card t) {
        if (t instanceof PlayerCard) {
            PlayerCard src = (PlayerCard) t;
            PlayerCard pc = new PlayerCard(src.id, src.name, src.description, src.pwr, src.skl, src.staMax, src.ability);
            pc.staCurrent = src.staCurrent;
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

    private TacticCard toTacticCard(CardId id) {
        Card c = copyCardFromStarterDeck(id);
        if (c instanceof TacticCard) return (TacticCard) c;
        return null;
    }

    private void scheduleOnlineReturnToMenu(long delayMs) {
        if (!onlineMode || onlineReturnToMenuScheduled) return;
        onlineReturnToMenuScheduled = true;
        postDelayed(() -> {
            if (menuListener != null) menuListener.onExitToMenu();
        }, Math.max(0L, delayMs));
    }

    private long getOnlineMatchEndReturnDelayMs() {
        if (!SettingsPrefs.getAnnouncerEnabled(getContext())) {
            return ONLINE_MATCH_END_RETURN_DELAY_NO_AUDIO_MS;
        }
        return ONLINE_MATCH_END_RETURN_DELAY_MS;
    }

    private void handleOnlineMatchFinished(SupabaseService.MatchSnapshot snapshot) {
        long returnDelayMs = getOnlineMatchEndReturnDelayMs();
        if (snapshot != null && snapshot.canonicalState != null && snapshot.canonicalState.length() > 0) {
            applyAuthoritativePhaseState(snapshot.canonicalState);
        }
        if (!state.matchOver) {
            if (state.homeScore == state.awayScore) {
                controller.endMatchTie();
            } else {
                controller.endMatchByScore();
            }
        } else if (state.bannerText == null || state.bannerText.isEmpty()) {
            controller.showBanner("ONLINE MATCH ENDED", SystemClock.uptimeMillis(), returnDelayMs);
        }
        scheduleOnlineReturnToMenu(returnDelayMs);
    }

    private void pollOnlineMatchStatus() {
        if (!onlineMode || guestAuthManager == null || supabaseService == null || onlineExecutor == null) return;
        if (onlineExecutor.isShutdown()) return;
        if (onlineStatusPollInFlight) return;
        onlineStatusPollInFlight = true;

        boolean submitted = executeOnlineSafely(() -> {
            try {
                GuestAuthManager.AuthState auth = ensureOnlineAuth(false);
                SupabaseService.MatchSnapshot snapshot = supabaseService.fetchMatchSnapshot(
                        auth.session.accessToken,
                        onlineMatchId
                );
                if (snapshot == null) return;

                String status = snapshot.status == null ? "" : snapshot.status.toLowerCase();
                if (!"forfeit".equals(status) && !"finished".equals(status) && !"canceled".equals(status)) return;

                if ("forfeit".equals(status) && !onlineForfeitHandled) {
                    onlineForfeitHandled = true;
                    onlineGameplayReady = false;
                    shutdownOnlineRealtimeClient();
                    boolean youWonByOpponentForfeit = auth.session.userId != null
                            && auth.session.userId.equals(snapshot.winnerUserId);
                    String text = youWonByOpponentForfeit
                            ? onlineOpponentLabel.toUpperCase() + " FORFEIT THE MATCH"
                            : "YOU FORFEIT THE MATCH";
                    post(() -> {
                        state.matchOver = true;
                        controller.showBanner(text, SystemClock.uptimeMillis(), 2300);
                        scheduleOnlineReturnToMenu(2300L);
                    });
                    return;
                }

                if (!onlineMatchEndedHandled) {
                    onlineMatchEndedHandled = true;
                    onlineGameplayReady = false;
                    shutdownOnlineRealtimeClient();
                    post(() -> handleOnlineMatchFinished(snapshot));
                }
            } catch (Exception e) {
                if (isAuthError(e)) {
                    try {
                        GuestAuthManager.AuthState refreshed = ensureOnlineAuth(true);
                        SupabaseService.MatchSnapshot snapshot = supabaseService.fetchMatchSnapshot(
                                refreshed.session.accessToken,
                                onlineMatchId
                        );
                        if (snapshot != null) {
                            String status = snapshot.status == null ? "" : snapshot.status.toLowerCase();
                            if ("forfeit".equals(status) && !onlineForfeitHandled) {
                                onlineForfeitHandled = true;
                                onlineGameplayReady = false;
                                shutdownOnlineRealtimeClient();
                                boolean youWonByOpponentForfeit = refreshed.session.userId != null
                                        && refreshed.session.userId.equals(snapshot.winnerUserId);
                                String text = youWonByOpponentForfeit
                                        ? onlineOpponentLabel.toUpperCase() + " FORFEIT THE MATCH"
                                        : "YOU FORFEIT THE MATCH";
                                post(() -> {
                                    state.matchOver = true;
                                    controller.showBanner(text, SystemClock.uptimeMillis(), 2300);
                                    scheduleOnlineReturnToMenu(2300L);
                                });
                            } else if (("finished".equals(status) || "canceled".equals(status)) && !onlineMatchEndedHandled) {
                                onlineMatchEndedHandled = true;
                                onlineGameplayReady = false;
                                shutdownOnlineRealtimeClient();
                                post(() -> handleOnlineMatchFinished(snapshot));
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                onlineStatusPollInFlight = false;
            }
        });
        if (!submitted) {
            onlineStatusPollInFlight = false;
        }
    }

    private void shutdownOnlineExecutor() {
        removeCallbacks(onlineRealtimeReconnect);
        removeCallbacks(onlinePresenceHeartbeat);
        onlineRealtimeReconnectScheduled = false;
        onlineRealtimeForceRefresh = false;
        shutdownOnlineRealtimeClient();
        onlineActionBuffer.clear();
        onlineLastActionSeq = -1;
        onlineLastAppliedActionSeq = -1;
        onlineActionRepollRequested = false;
        if (onlineExecutor != null) {
            onlineExecutor.shutdownNow();
            onlineExecutor = null;
        }
    }
}
