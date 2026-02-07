package com.roguegamestudio.rugbytcg;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.roguegamestudio.rugbytcg.multiplayer.GuestAuthManager;
import com.roguegamestudio.rugbytcg.multiplayer.SupabaseProfile;
import com.roguegamestudio.rugbytcg.multiplayer.SupabasePresenceClient;
import com.roguegamestudio.rugbytcg.multiplayer.SupabaseService;

import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MenuActivity extends AppCompatActivity {
    private static final String PREFS = "rugby_prefs";
    private static final String KEY_TUTORIAL_DONE = "tutorial_done";
    private static final long CHALLENGE_POLL_MS = 4_000L;
    private static final long READY_POLL_MS = 1_500L;

    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private GuestAuthManager guestAuthManager;
    private SupabaseService supabaseService;

    private TextView onlineStatusText;
    private TextView matchStatusText;
    private TextView onlineCountText;
    private Button publicMatchButton;
    private Button challengeButton;
    private Button forfeitMatchButton;

    private SupabaseProfile currentProfile;
    private GuestAuthManager.AuthState currentAuthState;
    private SupabaseService.ActiveMatch currentActiveMatch;

    private boolean viewDestroyed = false;
    private boolean incomingDialogShowing = false;
    private boolean activeMatchDialogShowing = false;
    private String lastIncomingChallengeId = null;
    private String lastActiveMatchDialogId = null;
    private AlertDialog waitingForOpponentDialog;
    private String waitingForOpponentMatchId;
    private boolean launchingOnlineMatch = false;
    private SupabasePresenceClient onlinePresenceClient;
    private String onlinePresenceUserId = null;
    private String onlinePresenceToken = null;
    private int currentOnlinePlayerCount = -1;

    private final Runnable challengePollTask = new Runnable() {
        @Override
        public void run() {
            pollIncomingChallenges();
        }
    };

    private final Runnable readyPollTask = new Runnable() {
        @Override
        public void run() {
            pollReadyAndStartIfBothReady();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        guestAuthManager = new GuestAuthManager(this);
        supabaseService = new SupabaseService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY);
        setContentView(buildMenuView());
        hideSystemBars();
        bootstrapGuestSession();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        ensureOnlinePresenceTracking();
        scheduleChallengePoll(500L);
    }

    @Override
    protected void onPause() {
        stopOnlinePresenceTracking();
        uiHandler.removeCallbacks(challengePollTask);
        uiHandler.removeCallbacks(readyPollTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        viewDestroyed = true;
        uiHandler.removeCallbacks(challengePollTask);
        uiHandler.removeCallbacks(readyPollTask);
        dismissWaitingForOpponentDialog();
        stopOnlinePresenceTracking();
        authExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private View buildMenuView() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(15, 30, 20));

        int pad = dp(24);
        root.setPadding(pad, pad * 2, pad, pad);

        TextView title = new TextView(this);
        title.setText("RUGBY TCG v" + BuildConfig.VERSION_NAME);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        title.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        titleParams.bottomMargin = dp(24);
        root.addView(title, titleParams);

        onlineStatusText = new TextView(this);
        onlineStatusText.setText("ONLINE: CONNECTING...");
        onlineStatusText.setTextColor(Color.rgb(185, 220, 200));
        onlineStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        onlineStatusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        statusParams.bottomMargin = dp(16);
        root.addView(onlineStatusText, statusParams);

        matchStatusText = new TextView(this);
        matchStatusText.setText("");
        matchStatusText.setTextColor(Color.rgb(235, 210, 130));
        matchStatusText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        matchStatusText.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams matchStatusParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        matchStatusParams.bottomMargin = dp(12);
        root.addView(matchStatusText, matchStatusParams);

        Button singlePlayer = new Button(this);
        singlePlayer.setText("SINGLE PLAYER");
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.bottomMargin = dp(12);
        root.addView(singlePlayer, btnParams);

        Button tutorialBtn = new Button(this);
        tutorialBtn.setText("HOW TO PLAY");
        root.addView(tutorialBtn, btnParams);

        publicMatchButton = new Button(this);
        publicMatchButton.setText("PUBLIC MATCHMAKING");
        publicMatchButton.setEnabled(false);
        root.addView(publicMatchButton, btnParams);

        challengeButton = new Button(this);
        challengeButton.setText("CHALLENGE BY ID");
        challengeButton.setEnabled(false);
        root.addView(challengeButton, btnParams);

        forfeitMatchButton = new Button(this);
        forfeitMatchButton.setText("FORFEIT ACTIVE MATCH");
        forfeitMatchButton.setEnabled(false);
        forfeitMatchButton.setVisibility(View.GONE);
        root.addView(forfeitMatchButton, btnParams);

        View spacer = new View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
        );
        root.addView(spacer, spacerParams);

        onlineCountText = new TextView(this);
        onlineCountText.setText("");
        onlineCountText.setTextColor(Color.rgb(175, 205, 190));
        onlineCountText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        onlineCountText.setGravity(Gravity.CENTER);
        onlineCountText.setVisibility(View.GONE);
        LinearLayout.LayoutParams onlineCountParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        onlineCountParams.topMargin = dp(8);
        root.addView(onlineCountText, onlineCountParams);

        singlePlayer.setOnClickListener(v -> {
            boolean done = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(KEY_TUTORIAL_DONE, false);
            startGame(!done);
        });

        tutorialBtn.setOnClickListener(v -> startGame(true));
        publicMatchButton.setOnClickListener(v -> onPublicMatchmakingSelected());
        challengeButton.setOnClickListener(v -> onChallengeByIdSelected());
        forfeitMatchButton.setOnClickListener(v -> onForfeitActiveMatchSelected());

        return root;
    }

    private void bootstrapGuestSession() {
        authExecutor.execute(() -> {
            String statusLine;
            GuestAuthManager.AuthState authState = null;
            try {
                authState = guestAuthManager.ensureGuestSession();
                statusLine = "ONLINE ID: " + authState.profile.publicId;
            } catch (Exception e) {
                String message = (e.getMessage() == null || e.getMessage().trim().isEmpty())
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                statusLine = "ONLINE: OFFLINE (" + message + ")";
            }

            GuestAuthManager.AuthState finalAuthState = authState;
            String finalStatusLine = statusLine;
            runOnUiThread(() -> {
                if (viewDestroyed || isFinishing()) return;
                if (finalAuthState != null) {
                    applyAuthState(finalAuthState);
                } else {
                    currentAuthState = null;
                    currentProfile = null;
                    currentActiveMatch = null;
                    refreshMultiplayerButtons();
                }
                onlineStatusText.setText(finalStatusLine);
                scheduleChallengePoll(1_000L);
            });
        });
    }

    private void onPublicMatchmakingSelected() {
        if (currentProfile == null) {
            Toast.makeText(this, "Still connecting to multiplayer services.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentActiveMatch != null) {
            Toast.makeText(this, "You already have an active online match.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Public matchmaking is next.", Toast.LENGTH_SHORT).show();
    }

    private void onChallengeByIdSelected() {
        if (currentProfile == null) {
            Toast.makeText(this, "Still connecting to multiplayer services.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentActiveMatch != null) {
            Toast.makeText(this, "You are already in a match.", Toast.LENGTH_SHORT).show();
            return;
        }
        showSendChallengeDialog();
    }

    private void onForfeitActiveMatchSelected() {
        if (currentProfile == null) {
            Toast.makeText(this, "Still connecting to multiplayer services.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentActiveMatch == null) {
            Toast.makeText(this, "No active match to forfeit.", Toast.LENGTH_SHORT).show();
            return;
        }
        forfeitActiveMatch();
    }

    private void showSendChallengeDialog() {
        EditText input = new EditText(this);
        input.setHint("G-XXXXXX or username");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Challenge Player")
                .setMessage("Enter player Guest ID or username")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Send", null)
                .create();

        dialog.setOnShowListener(d -> {
            Button sendBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            sendBtn.setOnClickListener(v -> {
                String target = input.getText() == null ? "" : input.getText().toString().trim();
                if (target.isEmpty()) {
                    input.setError("Required");
                    return;
                }
                dialog.dismiss();
                sendChallenge(target);
            });
        });

        dialog.show();
    }

    private void sendChallenge(String target) {
        publicMatchButton.setEnabled(false);
        challengeButton.setEnabled(false);
        authExecutor.execute(() -> {
            try {
                GuestAuthManager.AuthState authState = guestAuthManager.ensureGuestSession();
                SupabaseService.SendChallengeResult result = supabaseService.sendChallenge(
                        authState.session.accessToken,
                        target
                );
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    applyAuthState(authState);
                    String suffix = result.expiresAt == null || result.expiresAt.isEmpty()
                            ? ""
                            : "\nExpires: " + result.expiresAt;
                    Toast.makeText(
                            this,
                            "Challenge sent." + suffix,
                            Toast.LENGTH_LONG
                    ).show();
                    scheduleChallengePoll(250L);
                });
            } catch (Exception e) {
                String message = mapChallengeError(e);
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    refreshMultiplayerButtons();
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void pollIncomingChallenges() {
        if (viewDestroyed || isFinishing()) return;
        if (currentAuthState == null || currentProfile == null) {
            scheduleChallengePoll(CHALLENGE_POLL_MS);
            return;
        }

        authExecutor.execute(() -> {
            try {
                GuestAuthManager.AuthState authState = guestAuthManager.ensureGuestSession();
                SupabaseService.ActiveMatch activeMatch = supabaseService.fetchActiveMatch(
                        authState.session.accessToken,
                        authState.session.userId
                );
                SupabaseService.IncomingChallenge challenge = supabaseService.fetchLatestIncomingChallenge(
                        authState.session.accessToken,
                        authState.session.userId
                );
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    applyAuthState(authState);
                    applyActiveMatch(activeMatch);

                    if (challenge != null
                            && activeMatch == null
                            && !incomingDialogShowing
                            && !challenge.challengeId.equals(lastIncomingChallengeId)) {
                        showIncomingChallengeDialog(challenge);
                    }

                    if (activeMatch != null
                            && !incomingDialogShowing
                            && !activeMatchDialogShowing
                            && waitingForOpponentMatchId == null
                            && !activeMatch.matchId.equals(lastActiveMatchDialogId)) {
                        showActiveMatchReadyDialog(activeMatch);
                    }
                    scheduleChallengePoll(CHALLENGE_POLL_MS);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    scheduleChallengePoll(CHALLENGE_POLL_MS);
                });
            }
        });
    }

    private void showIncomingChallengeDialog(SupabaseService.IncomingChallenge challenge) {
        incomingDialogShowing = true;
        lastIncomingChallengeId = challenge.challengeId;

        String challenger = challenge.fromPublicId;
        if (challenger == null || challenger.isEmpty()) challenger = challenge.fromUsername;
        if (challenger == null || challenger.isEmpty()) challenger = challenge.fromDisplayName;
        if (challenger == null || challenger.isEmpty()) challenger = "Opponent";

        String message = challenger + " challenged you to a match.";

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Incoming Challenge")
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton("Decline", (d, which) -> respondToChallenge(challenge, false))
                .setPositiveButton("Accept", (d, which) -> respondToChallenge(challenge, true))
                .setOnDismissListener(d -> incomingDialogShowing = false)
                .create();
        dialog.show();
    }

    private void respondToChallenge(SupabaseService.IncomingChallenge challenge, boolean accept) {
        authExecutor.execute(() -> {
            try {
                GuestAuthManager.AuthState authState = guestAuthManager.ensureGuestSession();
                SupabaseService.RespondChallengeResult result = supabaseService.respondChallenge(
                        authState.session.accessToken,
                        challenge.challengeId,
                        accept
                );

                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    applyAuthState(authState);
                    String status = result.status == null ? "" : result.status.toLowerCase();
                    if ("accepted".equals(status)) {
                        SupabaseService.ActiveMatch activeMatch = new SupabaseService.ActiveMatch(
                                result.matchId,
                                "active",
                                challenge.fromUserId,
                                challenge.fromPublicId,
                                challenge.fromUsername,
                                challenge.fromDisplayName
                        );
                        applyActiveMatch(activeMatch);
                        String matchInfo = (result.matchId == null || result.matchId.isEmpty())
                                ? ""
                                : " Match ID: " + result.matchId;
                        Toast.makeText(this, "Challenge accepted." + matchInfo, Toast.LENGTH_LONG).show();
                        if (!activeMatchDialogShowing && waitingForOpponentMatchId == null) {
                            showActiveMatchReadyDialog(activeMatch);
                        }
                    } else if ("declined".equals(status)) {
                        Toast.makeText(this, "Challenge declined.", Toast.LENGTH_SHORT).show();
                    } else if ("expired".equals(status)) {
                        Toast.makeText(this, "Challenge already expired.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Challenge response: " + result.status, Toast.LENGTH_SHORT).show();
                    }
                    lastIncomingChallengeId = null;
                    scheduleChallengePoll(250L);
                });
            } catch (Exception e) {
                String message = mapChallengeError(e);
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    lastIncomingChallengeId = null;
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                    scheduleChallengePoll(500L);
                });
            }
        });
    }

    private void applyAuthState(GuestAuthManager.AuthState authState) {
        currentAuthState = authState;
        currentProfile = authState.profile;
        if (authState.profile != null) {
            onlineStatusText.setText("ONLINE ID: " + authState.profile.publicId);
            ensureOnlinePresenceTracking();
        } else {
            onlineStatusText.setText("ONLINE: OFFLINE");
            stopOnlinePresenceTracking();
        }
        refreshMultiplayerButtons();
        refreshOnlineCountText();
    }

    private void applyActiveMatch(SupabaseService.ActiveMatch activeMatch) {
        currentActiveMatch = activeMatch;
        if (activeMatch == null) {
            waitingForOpponentMatchId = null;
            lastActiveMatchDialogId = null;
            dismissWaitingForOpponentDialog();
            matchStatusText.setText("");
        } else {
            matchStatusText.setText("ACTIVE MATCH VS " + formatOpponent(activeMatch));
        }
        refreshMultiplayerButtons();
    }

    private void refreshMultiplayerButtons() {
        boolean connected = currentProfile != null;
        boolean inMatch = currentActiveMatch != null;
        boolean waitingForOpponent = waitingForOpponentMatchId != null;
        publicMatchButton.setEnabled(connected && !inMatch && !waitingForOpponent);
        challengeButton.setEnabled(connected && !inMatch && !waitingForOpponent);
        forfeitMatchButton.setEnabled(connected && (inMatch || waitingForOpponent));
        forfeitMatchButton.setVisibility((inMatch || waitingForOpponent) ? View.VISIBLE : View.GONE);
    }

    private void ensureOnlinePresenceTracking() {
        if (viewDestroyed || isFinishing()) return;
        if (currentAuthState == null
                || currentAuthState.session == null
                || currentProfile == null
                || isBlank(currentAuthState.session.userId)
                || isBlank(currentAuthState.session.accessToken)) {
            stopOnlinePresenceTracking();
            refreshOnlineCountText();
            return;
        }

        String userId = currentAuthState.session.userId;
        String accessToken = currentAuthState.session.accessToken;
        boolean sameSession = onlinePresenceClient != null
                && userId.equals(onlinePresenceUserId)
                && accessToken.equals(onlinePresenceToken);
        if (sameSession) {
            refreshOnlineCountText();
            return;
        }

        stopOnlinePresenceTracking();
        onlinePresenceUserId = userId;
        onlinePresenceToken = accessToken;
        currentOnlinePlayerCount = -1;
        refreshOnlineCountText();

        onlinePresenceClient = new SupabasePresenceClient(
                BuildConfig.SUPABASE_URL,
                BuildConfig.SUPABASE_PUBLISHABLE_KEY,
                accessToken,
                userId,
                new SupabasePresenceClient.Listener() {
                    @Override
                    public void onConnected() {
                        runOnUiThread(() -> {
                            if (viewDestroyed || isFinishing()) return;
                            refreshOnlineCountText();
                        });
                    }

                    @Override
                    public void onDisconnected() {
                        runOnUiThread(() -> {
                            if (viewDestroyed || isFinishing()) return;
                            currentOnlinePlayerCount = -1;
                            refreshOnlineCountText();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (viewDestroyed || isFinishing()) return;
                            currentOnlinePlayerCount = -1;
                            refreshOnlineCountText();
                        });
                    }

                    @Override
                    public void onOnlineCountChanged(int onlineCount) {
                        runOnUiThread(() -> {
                            if (viewDestroyed || isFinishing()) return;
                            currentOnlinePlayerCount = Math.max(0, onlineCount);
                            refreshOnlineCountText();
                        });
                    }
                }
        );
        onlinePresenceClient.connect();
    }

    private void stopOnlinePresenceTracking() {
        SupabasePresenceClient client = onlinePresenceClient;
        onlinePresenceClient = null;
        if (client != null) {
            client.disconnect();
        }
        onlinePresenceUserId = null;
        onlinePresenceToken = null;
        currentOnlinePlayerCount = -1;
    }

    private void refreshOnlineCountText() {
        if (onlineCountText == null) return;
        if (currentProfile == null) {
            onlineCountText.setVisibility(View.GONE);
            onlineCountText.setText("");
            return;
        }

        onlineCountText.setVisibility(View.VISIBLE);
        if (currentOnlinePlayerCount >= 0) {
            onlineCountText.setText("PLAYERS ONLINE: " + currentOnlinePlayerCount);
        } else {
            onlineCountText.setText("PLAYERS ONLINE: ...");
        }
    }

    private void scheduleChallengePoll(long delayMs) {
        uiHandler.removeCallbacks(challengePollTask);
        if (!viewDestroyed) {
            uiHandler.postDelayed(challengePollTask, delayMs);
        }
    }

    private void scheduleReadyPoll(long delayMs) {
        uiHandler.removeCallbacks(readyPollTask);
        if (!viewDestroyed && waitingForOpponentMatchId != null) {
            uiHandler.postDelayed(readyPollTask, delayMs);
        }
    }

    private void showActiveMatchReadyDialog(SupabaseService.ActiveMatch match) {
        activeMatchDialogShowing = true;
        lastActiveMatchDialogId = match.matchId;

        new AlertDialog.Builder(this)
                .setTitle("Match Ready")
                .setMessage("Online match is ready vs " + formatOpponent(match) + ".")
                .setCancelable(false)
                .setNegativeButton("Forfeit", (d, w) -> forfeitActiveMatch())
                .setPositiveButton("Start", (d, w) -> beginMatchStartHandshake(match))
                .setOnDismissListener(d -> activeMatchDialogShowing = false)
                .show();
    }

    private void beginMatchStartHandshake(SupabaseService.ActiveMatch match) {
        if (match == null || match.matchId == null || match.matchId.trim().isEmpty()) {
            Toast.makeText(this, "Missing match ID.", Toast.LENGTH_SHORT).show();
            return;
        }
        waitingForOpponentMatchId = match.matchId;
        showWaitingForOpponentDialog(match);
        refreshMultiplayerButtons();

        authExecutor.execute(() -> {
            try {
                GuestAuthManager.AuthState authState = guestAuthManager.ensureGuestSession();
                supabaseService.submitMatchAction(
                        authState.session.accessToken,
                        match.matchId,
                        "player_ready",
                        new JSONObject()
                );
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    applyAuthState(authState);
                    scheduleReadyPoll(250L);
                });
            } catch (Exception e) {
                String message = mapChallengeError(e);
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    dismissWaitingForOpponentDialog();
                    waitingForOpponentMatchId = null;
                    refreshMultiplayerButtons();
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showWaitingForOpponentDialog(SupabaseService.ActiveMatch match) {
        dismissWaitingForOpponentDialog();
        waitingForOpponentDialog = new AlertDialog.Builder(this)
                .setTitle("Waiting for other player...")
                .setMessage("Waiting for " + formatOpponent(match) + " to press Start.")
                .setCancelable(false)
                .setNegativeButton("Forfeit", (d, w) -> forfeitActiveMatch())
                .create();
        waitingForOpponentDialog.show();
    }

    private void dismissWaitingForOpponentDialog() {
        uiHandler.removeCallbacks(readyPollTask);
        if (waitingForOpponentDialog != null) {
            waitingForOpponentDialog.dismiss();
            waitingForOpponentDialog = null;
        }
    }

    private void pollReadyAndStartIfBothReady() {
        String matchId = waitingForOpponentMatchId;
        if (matchId == null || matchId.trim().isEmpty()) return;

        authExecutor.execute(() -> {
            try {
                GuestAuthManager.AuthState authState = guestAuthManager.ensureGuestSession();
                SupabaseService.MatchSnapshot snapshot = supabaseService.fetchMatchSnapshot(
                        authState.session.accessToken,
                        matchId
                );
                if (snapshot == null) {
                    runOnUiThread(() -> {
                        if (viewDestroyed || isFinishing()) return;
                        dismissWaitingForOpponentDialog();
                        waitingForOpponentMatchId = null;
                        applyActiveMatch(null);
                        refreshMultiplayerButtons();
                    });
                    return;
                }

                String status = snapshot.status == null ? "" : snapshot.status.toLowerCase();
                if ("forfeit".equals(status) || "finished".equals(status) || "canceled".equals(status)) {
                    runOnUiThread(() -> {
                        if (viewDestroyed || isFinishing()) return;
                        dismissWaitingForOpponentDialog();
                        waitingForOpponentMatchId = null;
                        applyActiveMatch(null);
                        refreshMultiplayerButtons();
                        Toast.makeText(this, "Match is no longer active.", Toast.LENGTH_SHORT).show();
                    });
                    return;
                }

                int readyPlayers = supabaseService.countDistinctReadyPlayers(
                        authState.session.accessToken,
                        matchId
                );

                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    if (!matchId.equals(waitingForOpponentMatchId)) return;
                    if (readyPlayers >= 2) {
                        dismissWaitingForOpponentDialog();
                        waitingForOpponentMatchId = null;
                        startOnlineMatch(matchId);
                    } else {
                        scheduleReadyPoll(READY_POLL_MS);
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    if (waitingForOpponentMatchId != null) {
                        scheduleReadyPoll(READY_POLL_MS);
                    }
                });
            }
        });
    }

    private String formatOpponent(SupabaseService.ActiveMatch match) {
        if (match.opponentPublicId != null && !match.opponentPublicId.isEmpty()) return match.opponentPublicId;
        if (match.opponentUsername != null && !match.opponentUsername.isEmpty()) return match.opponentUsername;
        if (match.opponentDisplayName != null && !match.opponentDisplayName.isEmpty()) return match.opponentDisplayName;
        return "Opponent";
    }

    private void startOnlineMatch(String matchId) {
        if (launchingOnlineMatch) return;
        launchingOnlineMatch = true;
        dismissWaitingForOpponentDialog();

        String opponentLabel = "Opponent";
        if (currentActiveMatch != null
                && currentActiveMatch.matchId != null
                && currentActiveMatch.matchId.equals(matchId)) {
            opponentLabel = formatOpponent(currentActiveMatch);
        }

        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_TUTORIAL, false);
        intent.putExtra(GameActivity.EXTRA_ONLINE_MATCH_ID, matchId);
        intent.putExtra(GameActivity.EXTRA_ONLINE_OPPONENT_LABEL, opponentLabel);
        startActivity(intent);
        launchingOnlineMatch = false;
    }

    private void forfeitActiveMatch() {
        forfeitMatchButton.setEnabled(false);
        dismissWaitingForOpponentDialog();
        authExecutor.execute(() -> {
            try {
                GuestAuthManager.AuthState authState = guestAuthManager.ensureGuestSession();
                SupabaseService.ForfeitMatchResult result = supabaseService.forfeitMyActiveMatch(
                        authState.session.accessToken
                );
                SupabaseService.ActiveMatch activeMatch = supabaseService.fetchActiveMatch(
                        authState.session.accessToken,
                        authState.session.userId
                );

                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    waitingForOpponentMatchId = null;
                    applyAuthState(authState);
                    applyActiveMatch(activeMatch);
                    if ("forfeit".equalsIgnoreCase(result.status)) {
                        Toast.makeText(this, "Active match forfeited.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "No active match to forfeit.", Toast.LENGTH_SHORT).show();
                    }
                    scheduleChallengePoll(250L);
                });
            } catch (Exception e) {
                String message = mapChallengeError(e);
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    refreshMultiplayerButtons();
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String mapChallengeError(Exception e) {
        String msg = e.getMessage();
        if (msg == null) return "Challenge request failed.";
        String lower = msg.toLowerCase();

        if (lower.contains("target_not_found")) return "No player found with that ID.";
        if (lower.contains("cannot_challenge_self")) return "You cannot challenge yourself.";
        if (lower.contains("pending_challenge_exists")) return "A pending challenge already exists.";
        if (lower.contains("target_already_in_match")) return "That player is already in a match.";
        if (lower.contains("you_already_in_match")) return "You are already in a match.";
        if (lower.contains("challenge_not_found")) return "Challenge no longer exists.";
        if (lower.contains("not_challenge_target")) return "This challenge is not addressed to you.";
        if (lower.contains("expired")) return "Challenge expired.";
        if (lower.contains("forfeit_my_active_match")) {
            return "Missing RPC forfeit function. Run the SQL patch for forfeit_my_active_match.";
        }
        if (lower.contains("http 300") || lower.contains("pgrst203") || lower.contains("multiple choices")) {
            return "Duplicate submit_match_action RPC signatures detected. Re-run the SQL patch "
                    + "20260206_online_match_actions.sql, then try again.";
        }

        return "Challenge request failed. " + msg;
    }

    private void startGame(boolean tutorial) {
        Intent intent = new Intent(this, GameActivity.class);
        intent.putExtra(GameActivity.EXTRA_TUTORIAL, tutorial);
        startActivity(intent);
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()
        ));
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private void hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            );
        }
    }
}
