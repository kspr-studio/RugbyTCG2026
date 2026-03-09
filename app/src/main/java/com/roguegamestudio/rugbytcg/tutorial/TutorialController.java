package com.roguegamestudio.rugbytcg.tutorial;

import android.graphics.RectF;
import android.view.MotionEvent;

import com.roguegamestudio.rugbytcg.CardId;
import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.ui.input.DragState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;
import com.roguegamestudio.rugbytcg.ui.layout.VisualCard;

public class TutorialController {
    public static final int TUT_INTRO = 0;
    public static final int TUT_SCORE = 1;
    public static final int TUT_MATCH_TIMER = 2;
    public static final int TUT_ROUND_TIMER = 3;
    public static final int TUT_PHASE_TOTALS = 4;
    public static final int TUT_BALL = 5;
    public static final int TUT_HAND_MOMENTUM = 6;
    public static final int TUT_HAND_COSTS = 7;
    public static final int TUT_HAND_STAMINA = 8;
    public static final int TUT_HAND_POWER = 9;
    public static final int TUT_INSPECT = 10;
    public static final int TUT_PLAY_FLANKER = 11;
    public static final int TUT_MOMENTUM_USED = 12;
    public static final int TUT_TACTIC_REQ = 13;
    public static final int TUT_LOG = 14;
    public static final int TUT_ENDTURN = 15;
    public static final int TUT_FINISH = 16;

    public interface Listener {
        void onTutorialFinished();
    }

    public interface FinishHandler {
        void onFinishMenu();
        void onFinishStart();
    }

    private boolean active = false;
    private int step = -1;
    private boolean flankerInspected = false;
    private Listener listener;

    public final RectF tutorialNextBtn = new RectF();
    public final RectF tutorialStartBtn = new RectF();
    public final RectF tutorialSkipBtn = new RectF();
    public final RectF tutorialTextBox = new RectF();

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void start() {
        active = true;
        step = TUT_FINISH;
        flankerInspected = false;
    }

    public void stop() {
        active = false;
        step = -1;
        flankerInspected = false;
    }

    public boolean isActive() {
        return active;
    }

    public int getStep() {
        return step;
    }

    public boolean isLogEnabled() {
        return !active || step >= TUT_LOG;
    }

    public boolean isEndTurnEnabled() {
        return !active || step >= TUT_ENDTURN;
    }

    public boolean isInspectStep() {
        return active && step == TUT_INSPECT;
    }

    public void onCardInspected() {
        if (active && step == TUT_INSPECT) {
            flankerInspected = true;
        }
    }

    public void onInspectClosed() {
        if (active && step == TUT_INSPECT && flankerInspected) {
            flankerInspected = false;
            advance(TUT_PLAY_FLANKER);
        }
    }

    public void onLogClosed() {
        if (active && step == TUT_LOG) {
            advance(TUT_ENDTURN);
        }
    }

    public void onEndTurnTutorial() {
        if (active && step == TUT_ENDTURN) {
            advance(TUT_FINISH);
        }
    }

    public void onFlankerPlayed() {
        if (active && step == TUT_PLAY_FLANKER) {
            advance(TUT_MOMENTUM_USED);
        }
    }

    public void onTutorialFinished() {
        stop();
        if (listener != null) listener.onTutorialFinished();
    }

    public void advance(int nextStep) {
        step = nextStep;
    }

    public boolean handleTutorialTouch(MotionEvent e, float x, float y, LayoutSpec layout, FinishHandler finishHandler) {
        if (!active) return false;
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN) return false;

        if (step == TUT_FINISH && finishHandler != null) {
            if (!tutorialStartBtn.isEmpty() && tutorialStartBtn.contains(x, y)) {
                finishHandler.onFinishStart();
                return true;
            }
            if (!tutorialNextBtn.isEmpty() && tutorialNextBtn.contains(x, y)) {
                finishHandler.onFinishMenu();
                return true;
            }
        }

        if (!tutorialNextBtn.isEmpty() && tutorialNextBtn.contains(x, y)) {
            if (step == TUT_INTRO) {
                advance(TUT_SCORE);
            } else if (step == TUT_SCORE) {
                advance(TUT_MATCH_TIMER);
            } else if (step == TUT_MATCH_TIMER) {
                advance(TUT_ROUND_TIMER);
            } else if (step == TUT_ROUND_TIMER) {
                advance(TUT_PHASE_TOTALS);
            } else if (step == TUT_PHASE_TOTALS) {
                advance(TUT_BALL);
            } else if (step == TUT_BALL) {
                advance(TUT_HAND_MOMENTUM);
            } else if (step == TUT_HAND_MOMENTUM) {
                advance(TUT_HAND_COSTS);
            } else if (step == TUT_HAND_COSTS) {
                advance(TUT_HAND_STAMINA);
            } else if (step == TUT_HAND_STAMINA) {
                advance(TUT_HAND_POWER);
            } else if (step == TUT_HAND_POWER) {
                advance(TUT_INSPECT);
            } else if (step == TUT_MOMENTUM_USED) {
                advance(TUT_TACTIC_REQ);
            } else if (step == TUT_TACTIC_REQ) {
                advance(TUT_LOG);
            }
            return true;
        }

        if (step == TUT_LOG && layout.logBtn.contains(x, y)) {
            return false;
        }

        return false;
    }

    public boolean shouldBlockInput(MotionEvent e, float x, float y, LayoutSpec layout, DragState dragState, GameState state) {
        if (!active) return false;
        switch (step) {
            case TUT_INTRO:
            case TUT_SCORE:
            case TUT_MATCH_TIMER:
            case TUT_ROUND_TIMER:
            case TUT_PHASE_TOTALS:
            case TUT_BALL:
            case TUT_HAND_MOMENTUM:
            case TUT_HAND_COSTS:
            case TUT_HAND_STAMINA:
            case TUT_HAND_POWER:
            case TUT_MOMENTUM_USED:
            case TUT_TACTIC_REQ:
            case TUT_FINISH:
                return true;
            case TUT_ENDTURN:
                return !(layout.endTurnBtn.contains(x, y));
            case TUT_LOG:
                return !(layout.logBtn.contains(x, y));
            case TUT_INSPECT: {
                for (VisualCard dc : layout.yourHandVisual) {
                    if (dc.rect.contains(x, y)) return false;
                }
                return true;
            }
            case TUT_PLAY_FLANKER: {
                if (dragState.dragging != null) return false;
                RectF flankerPlay = getTutorialFlankerRect(layout);
                return flankerPlay == null || !flankerPlay.contains(x, y);
            }
            default:
                return false;
        }
    }

    public RectF getHighlightRect(LayoutSpec layout, DragState dragState, float dp) {
        switch (step) {
            case TUT_INTRO:
                return layout.scoreZone;
            case TUT_SCORE:
                return new RectF(layout.scoreZone.left - dp * 6f, layout.scoreZone.top + dp * 8f,
                        layout.scoreZone.left + dp * 76f, layout.scoreZone.bottom - dp * 8f);
            case TUT_MATCH_TIMER:
                return new RectF(layout.scoreZone.centerX() - dp * 40f, layout.scoreZone.centerY() - dp * 16f,
                        layout.scoreZone.centerX() + dp * 40f, layout.scoreZone.centerY() + dp * 16f);
            case TUT_ROUND_TIMER:
                return new RectF(layout.turnZone.right - dp * 84f, layout.turnZone.centerY() - dp * 14f,
                        layout.turnZone.right + dp * 2f, layout.turnZone.centerY() + dp * 14f);
            case TUT_PHASE_TOTALS:
                return new RectF(layout.statsTableArea.left - dp * 60f, layout.statsTableArea.top,
                        layout.statsTableArea.right - dp * 60f, layout.statsTableArea.bottom);
            case TUT_BALL:
                return new RectF(layout.statsZone.centerX() - dp * 24f, layout.statsZone.centerY() - dp * 18f,
                        layout.statsZone.centerX() + dp * 24f, layout.statsZone.centerY() + dp * 18f);
            case TUT_HAND_MOMENTUM:
            case TUT_HAND_COSTS:
            case TUT_HAND_STAMINA:
            case TUT_HAND_POWER:
            case TUT_MOMENTUM_USED:
            case TUT_TACTIC_REQ:
                return getTutorialHandZoneRect(layout, dp);
            case TUT_INSPECT:
                return (dragState.dragging != null) ? dragState.dragging.rect : getTutorialHandZoneRect(layout, dp);
            case TUT_PLAY_FLANKER:
                return (dragState.dragging != null) ? layout.boardZone : getTutorialFlankerRect(layout);
            case TUT_ENDTURN:
                return layout.endTurnBtn;
            case TUT_LOG:
                return layout.logBtn;
            default:
                return null;
        }
    }

    public RectF getTutorialFlankerRect(LayoutSpec layout) {
        for (VisualCard dc : layout.yourHandVisual) {
            if (dc.card != null && dc.card.id == CardId.FLANKER) {
                return dc.rect;
            }
        }
        return layout.handZone;
    }

    public RectF getTutorialHandZoneRect(LayoutSpec layout, float dp) {
        float shift = dp * 8f;
        float top = layout.handZone.top + shift;
        float bottom = layout.handZone.bottom + shift;
        return new RectF(layout.handZone.left, top, layout.handZone.right, bottom);
    }

    public String getTutorialText() {
        switch (step) {
            case TUT_INTRO:
                return "The bar at the very top of the screen shows the match scores and timer.";
            case TUT_SCORE:
                return "This is your match score. The player with the higher score at the end of the match wins.";
            case TUT_MATCH_TIMER:
                return "This is the match timer. The match ends at 03:00.";
            case TUT_ROUND_TIMER:
                return "This is the round timer. When the round ends for both players, the player with the higher total POWER pushes the ball forward.";
            case TUT_PHASE_TOTALS:
                return "This is both players total POWER. The player with the higher POWER wins the phase.";
            case TUT_BALL:
                return "This is the ball. If the phase ends on -3, your opponent scores a TRY. If the phase ends on +3, you score a TRY.";
            case TUT_HAND_MOMENTUM:
                return "This is your hand and available MOMENTUM. Playing cards cost MOMENTUM.";
            case TUT_HAND_COSTS:
                return "PLAYER cards show MOMENTUM cost in the top-right. TACTIC and PLAY cards show MOMENTUM cost in the middle-left.";
            case TUT_HAND_STAMINA:
                return "PLAYER cards have a STAMINA bar at the top of the card. If it depletes, the card is removed from play.";
            case TUT_HAND_POWER:
                return "A PLAYER card's POWER is shown in the top-right. This contributes to your total POWER for the phase.";
            case TUT_MOMENTUM_USED:
                return "FLANKER has just consumed 4 MOMENTUM.";
            case TUT_INSPECT:
                return "Tap and hold to inspect a card.";
            case TUT_PLAY_FLANKER:
                return "Drag FLANKER onto the board to continue.";
            case TUT_TACTIC_REQ:
                return "There are also TACTIC and PLAY cards which require a PLAYER card on the field.";
            case TUT_ENDTURN:
                return "Tap END TURN to resolve the phase.";
            case TUT_LOG:
                return "Tap LOG to view the play history.";
            case TUT_FINISH:
                return "Good luck & have fun!";
            default:
                return "";
        }
    }
}
