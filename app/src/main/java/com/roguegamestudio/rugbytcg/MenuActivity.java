package com.roguegamestudio.rugbytcg;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.roguegamestudio.rugbytcg.multiplayer.GuestAuthManager;
import com.roguegamestudio.rugbytcg.multiplayer.SupabaseProfile;
import com.roguegamestudio.rugbytcg.multiplayer.SupabaseService;

import org.json.JSONObject;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MenuActivity extends AppCompatActivity {
    private static final String PREFS = "rugby_prefs";
    private static final String KEY_TUTORIAL_DONE = "tutorial_done";
    private static final String ASSET_MAIN_MENU = "mainmenu.png";
    private static final String ASSET_BTN_SINGLE = "buttons/btn_single.png";
    private static final String ASSET_BTN_MULTI = "buttons/btn_multi.png";
    private static final String ASSET_BTN_TUTORIAL = "buttons/btn_tutorial.png";
    private static final String ASSET_BTN_SETTINGS = "buttons/btn_settings.png";
    private static final String ASSET_BTN_ONLINE = "buttons/btn_online.png";
    private static final String UPDATE_APK_URL = "https://raw.githubusercontent.com/rikki3/roguegamestudio/refs/heads/main/minisurvivor/com.roguegamestudio.rugbytcg.apk";
    private static final String UPDATE_APK_FILE_NAME = "com.roguegamestudio.rugbytcg.apk";
    private static final int REQ_UNKNOWN_APP_SOURCES = 7601;
    private static final long VERSION_CHECK_DEBOUNCE_MS = 1_500L;
    private static final long CHALLENGE_POLL_MS = 4_000L;
    private static final long READY_POLL_MS = 1_500L;
    private static final long PRESENCE_POLL_MS = 7_000L;
    static final int VERSION_STATE_UNKNOWN = 0;
    static final int VERSION_STATE_UP_TO_DATE = 1;
    static final int VERSION_STATE_OUTDATED = 2;

    private final ExecutorService authExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private GuestAuthManager guestAuthManager;
    private SupabaseService supabaseService;
    private DownloadManager downloadManager;
    private BroadcastReceiver updateDownloadReceiver;

    private TextView onlineStatusText;
    private TextView matchStatusText;
    private TextView onlineCountText;
    private TextView onlinePlateIdText;
    private TextView versionText;
    private ImageButton multiplayerButton;
    private ImageButton onlinePlateButton;
    private ImageView onlinePlateImage;

    private SupabaseProfile currentProfile;
    private GuestAuthManager.AuthState currentAuthState;
    private SupabaseService.ActiveMatch currentActiveMatch;
    private SupabaseService.VersionStatus lastVersionStatus;

    private boolean viewDestroyed = false;
    private boolean authBootstrapInFlight = false;
    private boolean versionCheckInFlight = false;
    private boolean updateReceiverRegistered = false;
    private boolean incomingDialogShowing = false;
    private boolean activeMatchDialogShowing = false;
    private String lastIncomingChallengeId = null;
    private String lastActiveMatchDialogId = null;
    private String lastOnlinePublicId = null;
    private AlertDialog waitingForOpponentDialog;
    private String waitingForOpponentMatchId;
    private boolean launchingOnlineMatch = false;
    private int versionGateState = VERSION_STATE_UNKNOWN;
    private int currentOnlinePlayerCount = -1;
    private long lastVersionCheckElapsedMs = 0L;
    private long pendingUpdateDownloadId = -1L;
    private long pendingInstallDownloadId = -1L;

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

    private final Runnable presencePollTask = new Runnable() {
        @Override
        public void run() {
            pollOnlinePresence();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        guestAuthManager = new GuestAuthManager(this);
        supabaseService = new SupabaseService(BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_PUBLISHABLE_KEY);
        downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        initUpdateDownloadReceiver();
        setContentView(buildMenuView());
        hideSystemBars();
        setVersionLabelState(VERSION_STATE_UNKNOWN);
        updateOnlinePlateUi(false, null);
        runVersionGateCheck(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemBars();
        registerUpdateDownloadReceiver();
        if (pendingInstallDownloadId > 0L && canRequestPackageInstallsNow()) {
            installDownloadedApk(pendingInstallDownloadId);
        }
        if (versionGateState == VERSION_STATE_UP_TO_DATE) {
            ensureOnlinePresenceTracking();
            scheduleChallengePoll(500L);
            bootstrapGuestSession();
        } else {
            stopOnlinePresenceTracking();
            uiHandler.removeCallbacks(challengePollTask);
            runVersionGateCheck(false);
        }
    }

    @Override
    protected void onPause() {
        unregisterUpdateDownloadReceiver();
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
        unregisterUpdateDownloadReceiver();
        authExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_UNKNOWN_APP_SOURCES) return;
        if (pendingInstallDownloadId > 0L && canRequestPackageInstallsNow()) {
            installDownloadedApk(pendingInstallDownloadId);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
    }

    private View buildMenuView() {
        FrameLayout root = new FrameLayout(this);

        ImageView background = new ImageView(this);
        background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Bitmap menuBg = loadAssetBitmap(ASSET_MAIN_MENU);
        if (menuBg != null) {
            background.setImageBitmap(menuBg);
        } else {
            background.setBackgroundColor(Color.rgb(15, 30, 20));
        }
        root.addView(background, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int menuButtonWidth = Math.round(screenWidth * 0.58f * 0.75f);
        int menuButtonHeight = Math.round(menuButtonWidth * (104f / 300f));
        int onlinePlateWidth = Math.round(screenWidth * 0.78f);
        int onlinePlateHeight = Math.round(onlinePlateWidth * (245f / 1427f));

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams stackParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        stackParams.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        stackParams.bottomMargin = dp(10);
        root.addView(stack, stackParams);

        ImageButton singlePlayer = createMenuButton(ASSET_BTN_SINGLE, menuButtonWidth, menuButtonHeight);
        singlePlayer.setContentDescription("Single player");
        multiplayerButton = createMenuButton(ASSET_BTN_MULTI, menuButtonWidth, menuButtonHeight);
        multiplayerButton.setContentDescription("Multiplayer");
        ImageButton tutorialBtn = createMenuButton(ASSET_BTN_TUTORIAL, menuButtonWidth, menuButtonHeight);
        tutorialBtn.setContentDescription("Tutorial");
        ImageButton settingsBtn = createMenuButton(ASSET_BTN_SETTINGS, menuButtonWidth, menuButtonHeight);
        settingsBtn.setContentDescription("Settings");
        onlinePlateButton = createMenuButton(ASSET_BTN_ONLINE, onlinePlateWidth, onlinePlateHeight);
        onlinePlateButton.setContentDescription("Online ID");
        onlinePlateImage = onlinePlateButton;
        onlinePlateIdText = new TextView(this);
        onlinePlateIdText.setText("");
        onlinePlateIdText.setTextColor(Color.rgb(25, 25, 25));
        onlinePlateIdText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        onlinePlateIdText.setGravity(Gravity.CENTER);
        onlinePlateIdText.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD);

        FrameLayout onlinePlateContainer = new FrameLayout(this);
        onlinePlateContainer.addView(onlinePlateButton, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));
        onlinePlateContainer.addView(onlinePlateIdText, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
        ));
        onlinePlateContainer.setLayoutParams(stackedParams(onlinePlateWidth, onlinePlateHeight, 0));

        stack.addView(singlePlayer, stackedParams(menuButtonWidth, menuButtonHeight, dp(4)));
        stack.addView(multiplayerButton, stackedParams(menuButtonWidth, menuButtonHeight, dp(4)));
        stack.addView(tutorialBtn, stackedParams(menuButtonWidth, menuButtonHeight, dp(4)));
        stack.addView(settingsBtn, stackedParams(menuButtonWidth, menuButtonHeight, dp(6)));
        stack.addView(onlinePlateContainer, stackedParams(onlinePlateWidth, onlinePlateHeight, 0));

        setMultiplayerButtonEnabled(false);
        updateOnlinePlateUi(false, null);

        versionText = new TextView(this);
        versionText.setText("v" + BuildConfig.VERSION_NAME);
        versionText.setTextColor(Color.WHITE);
        versionText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        versionText.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.NORMAL);
        versionText.setPadding(dp(3), dp(2), dp(3), dp(2));
        versionText.setBackgroundColor(Color.argb(80, 0, 0, 0));
        versionText.setOnClickListener(v -> {
            if (versionGateState == VERSION_STATE_OUTDATED) {
                showUpdatePromptIfOutdated();
            }
        });
        FrameLayout.LayoutParams versionParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        versionParams.gravity = Gravity.TOP | Gravity.END;
        versionParams.topMargin = dp(10);
        versionParams.rightMargin = dp(10);
        root.addView(versionText, versionParams);

        // Hidden text labels retained for multiplayer state bookkeeping while main menu text is hidden.
        onlineStatusText = new TextView(this);
        matchStatusText = new TextView(this);
        onlineCountText = new TextView(this);

        singlePlayer.setOnClickListener(v -> {
            boolean done = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean(KEY_TUTORIAL_DONE, false);
            startGame(!done);
        });
        tutorialBtn.setOnClickListener(v -> startGame(true));
        multiplayerButton.setOnClickListener(v -> onMultiplayerSelected());
        settingsBtn.setOnClickListener(v -> showSettingsDialog());
        onlinePlateButton.setOnClickListener(v -> copyOnlineIdToClipboard());

        attachPushAnimation(singlePlayer);
        attachPushAnimation(multiplayerButton);
        attachPushAnimation(tutorialBtn);
        attachPushAnimation(settingsBtn);
        attachPushAnimation(onlinePlateButton);

        return root;
    }

    private void bootstrapGuestSession() {
        if (versionGateState != VERSION_STATE_UP_TO_DATE) return;
        if (authBootstrapInFlight) return;
        if (currentAuthState != null && currentProfile != null) return;
        authBootstrapInFlight = true;
        authExecutor.execute(() -> {
            String statusLine;
            GuestAuthManager.AuthState authState = null;
            try {
                authState = guestAuthManager.ensureGuestSession();
                statusLine = authState.profile.publicId;
            } catch (Exception e) {
                String message = (e.getMessage() == null || e.getMessage().trim().isEmpty())
                        ? e.getClass().getSimpleName()
                        : e.getMessage();
                statusLine = "ONLINE: OFFLINE (" + message + ")";
            }

            GuestAuthManager.AuthState finalAuthState = authState;
            String finalStatusLine = statusLine;
            runOnUiThread(() -> {
                authBootstrapInFlight = false;
                if (viewDestroyed || isFinishing()) return;
                if (versionGateState != VERSION_STATE_UP_TO_DATE) {
                    currentAuthState = null;
                    currentProfile = null;
                    currentActiveMatch = null;
                    stopOnlinePresenceTracking();
                    refreshMultiplayerButtons();
                    updateOnlinePlateUi(false, null);
                    return;
                }
                if (finalAuthState != null && finalAuthState.profile != null) {
                    applyAuthState(finalAuthState);
                    ensureOnlinePresenceTracking();
                } else {
                    currentAuthState = null;
                    currentProfile = null;
                    currentActiveMatch = null;
                    stopOnlinePresenceTracking();
                    refreshMultiplayerButtons();
                    updateOnlinePlateUi(false, null);
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

    private void onMultiplayerSelected() {
        if (versionGateState != VERSION_STATE_UP_TO_DATE) {
            if (versionGateState == VERSION_STATE_OUTDATED) {
                showUpdatePromptIfOutdated();
            } else {
                Toast.makeText(this, "Checking version. Try again in a moment.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (currentProfile == null) {
            Toast.makeText(this, "Still connecting to multiplayer services.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (waitingForOpponentMatchId != null) {
            Toast.makeText(this, "Waiting for other player to start.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentActiveMatch != null) {
            if (!activeMatchDialogShowing) {
                showActiveMatchReadyDialog(currentActiveMatch);
            }
            return;
        }
        onChallengeByIdSelected();
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
        setMultiplayerButtonEnabled(false);
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
        if (versionGateState != VERSION_STATE_UP_TO_DATE) {
            return;
        }
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
        if (authState.profile != null && versionGateState == VERSION_STATE_UP_TO_DATE) {
            onlineStatusText.setText(authState.profile.publicId);
            lastOnlinePublicId = authState.profile.publicId;
            updateOnlinePlateUi(true, authState.profile.publicId);
        } else {
            onlineStatusText.setText("ONLINE: OFFLINE");
            lastOnlinePublicId = null;
            updateOnlinePlateUi(false, null);
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
        boolean versionOk = versionGateState == VERSION_STATE_UP_TO_DATE;
        boolean connected = currentProfile != null;
        boolean inMatch = currentActiveMatch != null;
        boolean waitingForOpponent = waitingForOpponentMatchId != null;
        if (!versionOk || !connected) {
            setMultiplayerButtonEnabled(false);
            updateOnlinePlateUi(false, null);
            return;
        }
        setMultiplayerButtonEnabled(!waitingForOpponent || inMatch);
        if (multiplayerButton != null && (inMatch || waitingForOpponent)) {
            multiplayerButton.setAlpha(0.75f);
        }
        updateOnlinePlateUi(true, currentProfile.publicId);
    }

    private void ensureOnlinePresenceTracking() {
        if (viewDestroyed || isFinishing()) return;
        if (versionGateState != VERSION_STATE_UP_TO_DATE) {
            stopOnlinePresenceTracking();
            return;
        }
        if (currentAuthState == null
                || currentAuthState.session == null
                || currentProfile == null
                || isBlank(currentAuthState.session.userId)
                || isBlank(currentAuthState.session.accessToken)) {
            stopOnlinePresenceTracking();
            refreshOnlineCountText();
            return;
        }
        currentOnlinePlayerCount = -1;
        refreshOnlineCountText();
        schedulePresencePoll(200L);
    }

    private void stopOnlinePresenceTracking() {
        uiHandler.removeCallbacks(presencePollTask);
        sendPresenceInactive();
        currentOnlinePlayerCount = -1;
    }

    private void pollOnlinePresence() {
        if (viewDestroyed || isFinishing()) return;
        if (versionGateState != VERSION_STATE_UP_TO_DATE) {
            schedulePresencePoll(PRESENCE_POLL_MS);
            return;
        }
        if (currentAuthState == null || currentAuthState.session == null || currentProfile == null) {
            schedulePresencePoll(PRESENCE_POLL_MS);
            return;
        }

        authExecutor.execute(() -> {
            try {
                GuestAuthManager.AuthState authState = guestAuthManager.ensureGuestSession();
                SupabaseService.PresenceHeartbeatResult result = supabaseService.heartbeatPresenceV2(
                        authState.session.accessToken,
                        null,
                        true
                );
                final int onlineCount = result.accepted ? Math.max(0, result.onlineCount) : -1;
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    applyAuthState(authState);
                    currentOnlinePlayerCount = onlineCount;
                    refreshOnlineCountText();
                    schedulePresencePoll(PRESENCE_POLL_MS);
                });
            } catch (Exception ignored) {
                runOnUiThread(() -> {
                    if (viewDestroyed || isFinishing()) return;
                    currentOnlinePlayerCount = -1;
                    refreshOnlineCountText();
                    schedulePresencePoll(PRESENCE_POLL_MS);
                });
            }
        });
    }

    private void sendPresenceInactive() {
        GuestAuthManager.AuthState auth = currentAuthState;
        if (auth == null || auth.session == null || isBlank(auth.session.accessToken)) return;
        authExecutor.execute(() -> {
            try {
                supabaseService.heartbeatPresenceV2(auth.session.accessToken, null, false);
            } catch (Exception ignored) {
            }
        });
    }

    private void refreshOnlineCountText() {
        if (onlineCountText == null) return;
        if (currentProfile == null || versionGateState != VERSION_STATE_UP_TO_DATE) {
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
        if (!viewDestroyed && versionGateState == VERSION_STATE_UP_TO_DATE) {
            uiHandler.postDelayed(challengePollTask, delayMs);
        }
    }

    private void schedulePresencePoll(long delayMs) {
        uiHandler.removeCallbacks(presencePollTask);
        if (!viewDestroyed) {
            uiHandler.postDelayed(presencePollTask, Math.max(0L, delayMs));
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
                SupabaseService.SubmitActionV2Result result = supabaseService.submitMatchActionV2(
                        authState.session.accessToken,
                        match.matchId,
                        "match_ready",
                        new JSONObject(),
                        null
                );
                if (!result.accepted) {
                    throw new IllegalStateException(result.reason);
                }
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
        setMultiplayerButtonEnabled(false);
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

    static int determineVersionGateState(SupabaseService.VersionStatus status) {
        if (status == null) return VERSION_STATE_UNKNOWN;
        if (status.accepted && status.upToDate) return VERSION_STATE_UP_TO_DATE;
        String reason = status.reason == null ? "" : status.reason.toLowerCase();
        if (reason.contains("client_upgrade_required")) return VERSION_STATE_OUTDATED;
        if (status.latestClientVersion > 0 && status.latestClientVersion != BuildConfig.VERSION_CODE) {
            return VERSION_STATE_OUTDATED;
        }
        return VERSION_STATE_UNKNOWN;
    }

    static boolean shouldAllowOnlineBootstrap(int state) {
        return state == VERSION_STATE_UP_TO_DATE;
    }

    private void runVersionGateCheck(boolean force) {
        if (viewDestroyed || isFinishing()) return;
        if (versionCheckInFlight) return;
        long now = SystemClock.elapsedRealtime();
        if (!force && (now - lastVersionCheckElapsedMs) < VERSION_CHECK_DEBOUNCE_MS) return;
        lastVersionCheckElapsedMs = now;
        versionCheckInFlight = true;

        versionGateState = VERSION_STATE_UNKNOWN;
        setVersionLabelState(versionGateState);
        setMultiplayerButtonEnabled(false);
        updateOnlinePlateUi(false, null);

        authExecutor.execute(() -> {
            SupabaseService.VersionStatus status = null;
            Exception error = null;
            try {
                status = supabaseService.fetchClientVersionStatusPublic(BuildConfig.VERSION_CODE);
            } catch (Exception e) {
                error = e;
            }

            SupabaseService.VersionStatus statusFinal = status;
            Exception errorFinal = error;
            runOnUiThread(() -> {
                if (viewDestroyed || isFinishing()) return;
                versionCheckInFlight = false;
                lastVersionStatus = statusFinal;
                int newState = determineVersionGateState(statusFinal);
                versionGateState = newState;
                setVersionLabelState(newState);

                if (shouldAllowOnlineBootstrap(newState)) {
                    if (currentProfile != null) {
                        updateOnlinePlateUi(true, currentProfile.publicId);
                        refreshMultiplayerButtons();
                        ensureOnlinePresenceTracking();
                        scheduleChallengePoll(300L);
                    } else {
                        bootstrapGuestSession();
                    }
                    return;
                }

                currentAuthState = null;
                currentProfile = null;
                currentActiveMatch = null;
                waitingForOpponentMatchId = null;
                dismissWaitingForOpponentDialog();
                stopOnlinePresenceTracking();
                uiHandler.removeCallbacks(challengePollTask);
                refreshMultiplayerButtons();
                updateOnlinePlateUi(false, null);

                if (newState == VERSION_STATE_OUTDATED) {
                    onlineStatusText.setText("UPDATE REQUIRED");
                } else if (errorFinal != null) {
                    onlineStatusText.setText("ONLINE: OFFLINE (" + summarizeError(errorFinal) + ")");
                } else {
                    onlineStatusText.setText("ONLINE: OFFLINE");
                }
            });
        });
    }

    private void setVersionLabelState(int state) {
        if (versionText == null) return;
        versionText.setText("v" + BuildConfig.VERSION_NAME);
        if (state == VERSION_STATE_OUTDATED) {
            versionText.setTextColor(Color.rgb(230, 75, 75));
            versionText.setClickable(true);
            versionText.setAlpha(1f);
        } else {
            versionText.setTextColor(Color.WHITE);
            versionText.setClickable(false);
            versionText.setAlpha(0.92f);
        }
    }

    private void updateOnlinePlateUi(boolean onlineConnected, String publicId) {
        if (onlinePlateButton == null || onlinePlateImage == null || onlinePlateIdText == null) return;
        boolean active = onlineConnected
                && versionGateState == VERSION_STATE_UP_TO_DATE
                && !isBlank(publicId);
        if (active) {
            onlinePlateImage.setColorFilter(null);
            onlinePlateImage.setAlpha(1f);
            onlinePlateButton.setEnabled(true);
            onlinePlateIdText.setText(publicId.trim());
            onlinePlateIdText.setAlpha(1f);
            lastOnlinePublicId = publicId.trim();
            return;
        }

        ColorMatrix matrix = new ColorMatrix();
        matrix.setSaturation(0f);
        onlinePlateImage.setColorFilter(new ColorMatrixColorFilter(matrix));
        onlinePlateImage.setAlpha(0.7f);
        onlinePlateButton.setEnabled(false);
        onlinePlateIdText.setText("");
        onlinePlateIdText.setAlpha(0.9f);
        lastOnlinePublicId = null;
    }

    private void copyOnlineIdToClipboard() {
        if (!onlinePlateButton.isEnabled() || isBlank(lastOnlinePublicId)) return;
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        ClipData clip = ClipData.newPlainText("online_id", lastOnlinePublicId);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "ID copied", Toast.LENGTH_SHORT).show();
    }

    private void attachPushAnimation(View view) {
        if (view == null) return;
        view.setOnTouchListener((v, event) -> {
            if (!v.isEnabled()) return false;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.animate()
                        .scaleX(0.95f)
                        .scaleY(0.95f)
                        .translationY(dp(1))
                        .setDuration(70)
                        .start();
            } else if (action == MotionEvent.ACTION_UP
                    || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_OUTSIDE) {
                v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(110)
                        .start();
            }
            return false;
        });
    }

    private void showUpdatePromptIfOutdated() {
        if (versionGateState != VERSION_STATE_OUTDATED) return;
        int latest = lastVersionStatus != null ? lastVersionStatus.latestClientVersion : -1;
        String latestText = latest > 0 ? String.valueOf(latest) : "unknown";
        String message = "Current: v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "Latest: " + latestText + "\n\nUpdate game now?";
        new AlertDialog.Builder(this)
                .setTitle("Update Required")
                .setMessage(message)
                .setNegativeButton("No", null)
                .setPositiveButton("Yes", (d, w) -> startUpdateDownload())
                .show();
    }

    private void startUpdateDownload() {
        if (downloadManager == null) {
            Toast.makeText(this, "Download manager unavailable.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(UPDATE_APK_URL));
            request.setTitle("Rugby TCG Update");
            request.setDescription("Downloading latest version");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, UPDATE_APK_FILE_NAME);
            pendingUpdateDownloadId = downloadManager.enqueue(request);
            Toast.makeText(this, "Update download started.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to start download: " + summarizeError(e), Toast.LENGTH_LONG).show();
        }
    }

    private void initUpdateDownloadReceiver() {
        updateDownloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent == null) return;
                if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
                long downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);
                if (downloadId > 0L) {
                    handleUpdateDownloadComplete(downloadId);
                }
            }
        };
    }

    private void registerUpdateDownloadReceiver() {
        if (updateReceiverRegistered || updateDownloadReceiver == null) return;
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateDownloadReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateDownloadReceiver, filter);
        }
        updateReceiverRegistered = true;
    }

    private void unregisterUpdateDownloadReceiver() {
        if (!updateReceiverRegistered || updateDownloadReceiver == null) return;
        try {
            unregisterReceiver(updateDownloadReceiver);
        } catch (Exception ignored) {
        }
        updateReceiverRegistered = false;
    }

    private void handleUpdateDownloadComplete(long downloadId) {
        if (downloadId != pendingUpdateDownloadId) return;
        if (getDownloadedApkUri(downloadId) == null) {
            Toast.makeText(this, "Update download failed.", Toast.LENGTH_LONG).show();
            return;
        }
        if (!canRequestPackageInstallsNow()) {
            pendingInstallDownloadId = downloadId;
            openUnknownAppSourcesSettings();
            return;
        }
        installDownloadedApk(downloadId);
    }

    private Uri getDownloadedApkUri(long downloadId) {
        if (downloadManager == null || downloadId <= 0L) return null;
        Cursor cursor = null;
        try {
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(downloadId);
            cursor = downloadManager.query(query);
            if (cursor == null || !cursor.moveToFirst()) return null;
            int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (status != DownloadManager.STATUS_SUCCESSFUL) return null;
            return downloadManager.getUriForDownloadedFile(downloadId);
        } catch (Exception ignored) {
            return null;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private void installDownloadedApk(long downloadId) {
        Uri apkUri = getDownloadedApkUri(downloadId);
        if (apkUri == null) {
            Toast.makeText(this, "Unable to open downloaded update.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingInstallDownloadId = -1L;
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(installIntent);
        } catch (Exception e) {
            Toast.makeText(this, "Installer unavailable.", Toast.LENGTH_LONG).show();
        }
    }

    private boolean canRequestPackageInstallsNow() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true;
        return getPackageManager().canRequestPackageInstalls();
    }

    private void openUnknownAppSourcesSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + getPackageName())
        );
        try {
            startActivityForResult(intent, REQ_UNKNOWN_APP_SOURCES);
        } catch (Exception e) {
            Toast.makeText(this, "Enable install unknown apps for this app.", Toast.LENGTH_LONG).show();
        }
    }

    private String summarizeError(Exception e) {
        if (e == null) return "unknown error";
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return e.getClass().getSimpleName();
        return message.trim();
    }

    private void showSettingsDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(8));

        TextView crowdLabel = new TextView(this);
        crowdLabel.setText("Crowd Volume");
        crowdLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        crowdLabel.setTextColor(Color.BLACK);
        content.addView(crowdLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView crowdValue = new TextView(this);
        crowdValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        crowdValue.setTextColor(Color.DKGRAY);
        crowdValue.setGravity(Gravity.END);
        LinearLayout.LayoutParams valueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        valueParams.topMargin = dp(4);
        content.addView(crowdValue, valueParams);

        SeekBar crowdSlider = new SeekBar(this);
        crowdSlider.setMax(100);
        int initialValue = SettingsPrefs.getCrowdVolumePercent(this);
        crowdSlider.setProgress(initialValue);
        crowdValue.setText(initialValue + "%");
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        sliderParams.topMargin = dp(4);
        content.addView(crowdSlider, sliderParams);

        crowdSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int clamped = SettingsPrefs.clampPercent(progress);
                crowdValue.setText(clamped + "%");
                SettingsPrefs.setCrowdVolumePercent(MenuActivity.this, clamped);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        View sectionSpacer = new View(this);
        LinearLayout.LayoutParams spacerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(10)
        );
        content.addView(sectionSpacer, spacerParams);

        TextView announcerLabel = new TextView(this);
        announcerLabel.setText("Announcer");
        announcerLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        announcerLabel.setTextColor(Color.BLACK);
        content.addView(announcerLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        Switch announcerToggle = new Switch(this);
        announcerToggle.setText("Enable In Single Player");
        announcerToggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        announcerToggle.setChecked(SettingsPrefs.getAnnouncerEnabled(this));
        LinearLayout.LayoutParams toggleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        toggleParams.topMargin = dp(4);
        content.addView(announcerToggle, toggleParams);

        TextView announcerVolumeLabel = new TextView(this);
        announcerVolumeLabel.setText("Announcer Volume");
        announcerVolumeLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        announcerVolumeLabel.setTextColor(Color.BLACK);
        LinearLayout.LayoutParams announcerVolumeLabelParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        announcerVolumeLabelParams.topMargin = dp(8);
        content.addView(announcerVolumeLabel, announcerVolumeLabelParams);

        TextView announcerValue = new TextView(this);
        announcerValue.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        announcerValue.setTextColor(Color.DKGRAY);
        announcerValue.setGravity(Gravity.END);
        LinearLayout.LayoutParams announcerValueParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        announcerValueParams.topMargin = dp(4);
        content.addView(announcerValue, announcerValueParams);

        SeekBar announcerSlider = new SeekBar(this);
        announcerSlider.setMax(100);
        int initialAnnouncerVolume = SettingsPrefs.getAnnouncerVolumePercent(this);
        announcerSlider.setProgress(initialAnnouncerVolume);
        announcerValue.setText(initialAnnouncerVolume + "%");
        LinearLayout.LayoutParams announcerSliderParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        announcerSliderParams.topMargin = dp(4);
        content.addView(announcerSlider, announcerSliderParams);

        Runnable syncAnnouncerControls = () -> {
            boolean enabled = announcerToggle.isChecked();
            announcerSlider.setEnabled(enabled);
            float alpha = enabled ? 1f : 0.45f;
            announcerValue.setAlpha(alpha);
            announcerVolumeLabel.setAlpha(alpha);
        };
        syncAnnouncerControls.run();

        announcerToggle.setOnCheckedChangeListener((buttonView, isChecked) -> {
            SettingsPrefs.setAnnouncerEnabled(MenuActivity.this, isChecked);
            syncAnnouncerControls.run();
        });

        announcerSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int clamped = SettingsPrefs.clampPercent(progress);
                announcerValue.setText(clamped + "%");
                SettingsPrefs.setAnnouncerVolumePercent(MenuActivity.this, clamped);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(content)
                .setPositiveButton("Close", null)
                .show();
    }

    private void setMultiplayerButtonEnabled(boolean enabled) {
        if (multiplayerButton == null) return;
        multiplayerButton.setEnabled(enabled);
        multiplayerButton.setAlpha(enabled ? 1f : 0.45f);
    }

    private ImageButton createMenuButton(String assetPath, int width, int height) {
        ImageButton button = new ImageButton(this);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setScaleType(ImageView.ScaleType.FIT_XY);
        button.setAdjustViewBounds(false);
        button.setPadding(0, 0, 0, 0);
        button.setMinimumWidth(0);
        button.setMinimumHeight(0);
        Bitmap bitmap = loadAssetBitmap(assetPath);
        if (bitmap != null) {
            button.setImageBitmap(bitmap);
        }
        button.setLayoutParams(stackedParams(width, height, 0));
        return button;
    }

    private LinearLayout.LayoutParams stackedParams(int width, int height, int bottomMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.bottomMargin = bottomMargin;
        return params;
    }

    private Bitmap loadAssetBitmap(String assetPath) {
        try (InputStream in = getAssets().open(assetPath)) {
            return BitmapFactory.decodeStream(in);
        } catch (Exception e) {
            return null;
        }
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
        if (lower.contains("client_upgrade_required")) return "Multiplayer update required.";
        if (lower.contains("get_client_version_status_v1")) {
            return "Missing version status RPC. Run SQL patch 20260208_public_version_status.sql.";
        }
        if (lower.contains("forfeit_my_active_match")) {
            return "Missing RPC forfeit function. Run the SQL patch for forfeit_my_active_match.";
        }
        if (isJoinMatchV2AmbiguousSqlError(lower)) {
            return "Multiplayer backend SQL error. Run SQL patch 20260208_multiplayer_v2_join_match_fix.sql.";
        }
        if (isMissingMultiplayerV2RpcError(lower)) {
            return "Missing multiplayer v2 RPCs. Run SQL patch 20260208_multiplayer_v2_authority.sql.";
        }
        if (lower.contains("http 300") || lower.contains("pgrst203") || lower.contains("multiple choices")) {
            return "Duplicate submit_match_action RPC signatures detected. Re-run the SQL patch "
                    + "20260206_online_match_actions.sql, then try again.";
        }

        return "Challenge request failed. " + msg;
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
