package com.roguegamestudio.rugbytcg.ui;

import android.graphics.RectF;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.core.LogEntry;

import java.util.ArrayList;
import java.util.List;

public class UiState {
    // Log UI
    public boolean showLog = false;
    public boolean showInspect = false;
    public boolean inspectFromLog = false;
    public boolean inspectOpponent = false;
    public boolean isLogScrolling = false;
    public float logLastY = 0f;
    public float logScrollY = 0f;
    public int logTapIndex = -1;
    public float logTapStartY = 0f;
    public final RectF logPanel = new RectF();
    public final RectF logListArea = new RectF();
    public final List<LogEntry> playLog = new ArrayList<>();

    // Inspect UI
    public Card inspectCard = null;

    // Multiplayer labels (fallback to single-player defaults)
    public String localPlayerLabel = "HOME";
    public String opponentPlayerLabel = "AWAY";

    // Played card spotlight
    public Card playFlashCard = null;
    public boolean playFlashOpponent = false;
    public String playFlashLabel = "";
    public long playFlashUntilMs = 0L;
    public long playFlashStartMs = 0L;
    public long playFlashHoldMs = 1000L;
    public long playFlashAnimMs = 500L;
    public Card playFlashSourceCard = null;
    public final RectF playFlashTargetRect = new RectF();
    public boolean playFlashHasTarget = false;

    // Burn overlay
    public Card burnFlashCard = null;
    public long burnFlashUntilMs = 0L;
    public String burnFlashLabel = "BURNING RANDOM CARD";

    // Flash overlay
    public int flashColor = 0;
    public long flashStartMs = 0L;
    public long flashDurationMs = 0L;

    // Match banner stickiness
    public boolean matchWinBannerSticky = false;
    public long matchBannerDismissAllowedAtMs = 0L;

    // Online kickoff handshake
    public boolean onlineInitialKickoffPending = false;
    public boolean onlineKickoffWaiting = false;
    public boolean onlineActionAckPending = false;
    public int onlineKickoffGeneration = 0;
}
