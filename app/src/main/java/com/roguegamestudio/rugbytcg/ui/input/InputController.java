package com.roguegamestudio.rugbytcg.ui.input;

import android.view.MotionEvent;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.core.LogEntry;
import com.roguegamestudio.rugbytcg.engine.GameController;
import com.roguegamestudio.rugbytcg.engine.RulesEngine;
import com.roguegamestudio.rugbytcg.engine.TurnEngine;
import com.roguegamestudio.rugbytcg.tutorial.TutorialController;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;
import com.roguegamestudio.rugbytcg.ui.layout.VisualCard;
import com.roguegamestudio.rugbytcg.ui.log.LogLayoutHelper;
import com.roguegamestudio.rugbytcg.utils.UiCallbacks;

public class InputController {
    private final GameState state;
    private final UiState ui;
    private final LayoutSpec layout;
    private final GameController controller;
    private final TurnEngine turnEngine;
    private final UiCallbacks uiCallbacks;
    private final DragState dragState;
    private final TutorialController tutorial;
    private final TutorialController.FinishHandler finishHandler;
    private final Runnable menuHandler;
    private final float density;

    private final float scrollLockThresholdDp = 10f;

    public InputController(GameState state,
                           UiState ui,
                           LayoutSpec layout,
                           GameController controller,
                           TurnEngine turnEngine,
                           UiCallbacks uiCallbacks,
                           DragState dragState,
                           TutorialController tutorial,
                           TutorialController.FinishHandler finishHandler,
                           Runnable menuHandler,
                           float density) {
        this.state = state;
        this.ui = ui;
        this.layout = layout;
        this.controller = controller;
        this.turnEngine = turnEngine;
        this.uiCallbacks = uiCallbacks;
        this.dragState = dragState;
        this.tutorial = tutorial;
        this.finishHandler = finishHandler;
        this.menuHandler = menuHandler;
        this.density = density;
    }

    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();

        if (controller.clearMatchBannerIfSticky(e)) {
            return true;
        }

        long now = android.os.SystemClock.uptimeMillis();
        if (!ui.showInspect && !ui.showLog && ui.burnFlashCard != null && now < ui.burnFlashUntilMs) {
            return true;
        }
        if (!ui.showInspect && !ui.showLog && ui.playFlashCard != null && now < ui.playFlashUntilMs) {
            return true;
        }

        if (tutorial != null && tutorial.isActive() && !ui.showLog && !ui.showInspect) {
            if (tutorial.handleTutorialTouch(e, x, y, layout, finishHandler)) return true;
            if (tutorial.shouldBlockInput(e, x, y, layout, dragState, state)) return true;
        }

        if (ui.showInspect) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                ui.showInspect = false;
                ui.inspectCard = null;
                ui.inspectFromLog = false;
                ui.inspectOpponent = false;
                dragState.longPressTriggered = false;
                if (tutorial != null) {
                    tutorial.onInspectClosed();
                }
                uiCallbacks.invalidate();
            }
            return true;
        }

        if (ui.showLog) {
            LogLayoutHelper.ensureLayout(ui, layout.handZone.right, layout.handZone.bottom, dp(1));
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (!ui.logPanel.contains(x, y)) {
                        ui.showLog = false;
                        if (tutorial != null) tutorial.onLogClosed();
                        uiCallbacks.invalidate();
                        return true;
                    }
                    if (ui.logListArea.contains(x, y)) {
                        ui.isLogScrolling = false;
                        ui.logTapIndex = LogLayoutHelper.getIndexAt(ui, y, layout.cardH, dp(1));
                        ui.logTapStartY = y;
                        ui.logLastY = y;
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (ui.logListArea.contains(x, y)) {
                        float dy = y - ui.logLastY;
                        if (!ui.isLogScrolling && Math.abs(y - ui.logTapStartY) > dp(6)) {
                            ui.isLogScrolling = true;
                        }
                        if (ui.isLogScrolling) {
                            float max = LogLayoutHelper.getScrollMax(ui, layout.cardH, dp(1));
                            ui.logScrollY = Math.max(0f, Math.min(ui.logScrollY - dy, max));
                            ui.logLastY = y;
                            uiCallbacks.invalidate();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (!ui.isLogScrolling && ui.logTapIndex >= 0 && ui.logTapIndex < ui.playLog.size()) {
                        LogEntry entry = ui.playLog.get(ui.logTapIndex);
                        ui.inspectCard = entry.snapshot.card;
                        ui.showInspect = true;
                        ui.inspectFromLog = true;
                        ui.inspectOpponent = entry.opponent;
                        uiCallbacks.invalidate();
                    }
                    ui.isLogScrolling = false;
                    ui.logTapIndex = -1;
                    return true;
            }
        }

        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && layout.menuBtn.contains(x, y)) {
            if (tutorial == null || !tutorial.isActive()) {
                if (menuHandler != null) menuHandler.run();
            }
            return true;
        }

        if (e.getActionMasked() == MotionEvent.ACTION_DOWN && layout.logBtn.contains(x, y)) {
            if (tutorial != null && tutorial.isActive() && !tutorial.isLogEnabled()) {
                return true;
            }
            ui.showLog = true;
            ui.logScrollY = 0f;
            uiCallbacks.invalidate();
            return true;
        }

        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dragState.pressedBoardCard = findBoardCardAt(x, y);
            if (dragState.pressedBoardCard != null) {
                dragState.boardDownX = x;
                dragState.boardDownY = y;
                scheduleBoardLongPress(dragState.pressedBoardCard);
                return true;
            }
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragState.downX = x;
                dragState.downY = y;
                dragState.pressedCard = null;
                dragState.longPressTriggered = false;

                if (state.matchOver && layout.endTurnBtn.contains(x, y)) {
                    if (controller.isOnlineGameplayMode()) {
                        return true;
                    }
                    if (controller.isMatchBannerDismissBlocked()) {
                        return true;
                    }
                    controller.startNewMatch();
                    return true;
                }

                if (!state.matchOver && layout.endTurnBtn.contains(x, y)) {
                    if (!ui.onlineActionAckPending
                            && (ui.onlineInitialKickoffPending || turnEngine.getTurnState() == TurnEngine.TurnState.PLAYER)) {
                        controller.onEndTurn();
                        return true;
                    }
                }

                if (ui.onlineInitialKickoffPending || ui.onlineActionAckPending) {
                    return true;
                }

                for (int i = layout.yourHandVisual.size() - 1; i >= 0; i--) {
                    VisualCard dc = layout.yourHandVisual.get(i);
                    if (dc.rect.contains(x, y)) {
                        dragState.pressedCard = dc;
                        scheduleHandLongPress(dc);
                        return true;
                    }
                }

                if (layout.handZone.contains(x, y)) return true;

                if (state.matchOver || turnEngine.getTurnState() != TurnEngine.TurnState.PLAYER) return false;
                return false;

            case MotionEvent.ACTION_MOVE:
                if (tutorial != null && tutorial.isActive() && tutorial.isInspectStep()) {
                    return true;
                }
                if (ui.onlineInitialKickoffPending || ui.onlineActionAckPending) {
                    return true;
                }
                if (state.matchOver) {
                    float dx = x - dragState.downX;
                    float dy = y - dragState.downY;
                    float threshold = dp(scrollLockThresholdDp);
                    if (dragState.pressedCard != null && (Math.abs(dx) > threshold || Math.abs(dy) > threshold)) {
                        cancelHandLongPress();
                        return true;
                    }
                    return true;
                }

                if (turnEngine.getTurnState() != TurnEngine.TurnState.PLAYER) return false;

                float dx = x - dragState.downX;
                float dy = y - dragState.downY;

                float threshold = dp(scrollLockThresholdDp);

                if (dragState.dragging != null) {
                    float newLeft = x - dragState.grabDx;
                    float newTop  = y - dragState.grabDy;
                    dragState.dragging.rect.set(newLeft, newTop, newLeft + dragState.dragging.rect.width(), newTop + dragState.dragging.rect.height());
                    uiCallbacks.postInvalidateOnAnimation();
                    return true;
                }

                if (Math.abs(dx) > threshold || Math.abs(dy) > threshold) {
                    cancelHandLongPress();
                    cancelBoardLongPress();
                    if (dragState.pressedCard != null) {
                        dragState.dragging = dragState.pressedCard;
                        dragState.grabDx = dragState.downX - dragState.dragging.rect.left;
                        dragState.grabDy = dragState.downY - dragState.dragging.rect.top;
                        dragState.pressedCard = null;
                        float newLeft = x - dragState.grabDx;
                        float newTop  = y - dragState.grabDy;
                        dragState.dragging.rect.set(newLeft, newTop, newLeft + dragState.dragging.rect.width(), newTop + dragState.dragging.rect.height());
                        return true;
                    }
                }

                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                cancelHandLongPress();
                cancelBoardLongPress();
                if (ui.onlineInitialKickoffPending || ui.onlineActionAckPending) {
                    dragState.pressedCard = null;
                    dragState.dragging = null;
                    dragState.longPressTriggered = false;
                    return true;
                }
                if (dragState.longPressTriggered) {
                    dragState.longPressTriggered = false;
                    dragState.pressedCard = null;
                    dragState.dragging = null;
                    return true;
                }
                if (dragState.boardLongPressTriggered) {
                    dragState.boardLongPressTriggered = false;
                    dragState.pressedBoardCard = null;
                    return true;
                }
                if (state.matchOver) {
                    dragState.pressedCard = null;
                    return true;
                }
                if (turnEngine.getTurnState() != TurnEngine.TurnState.PLAYER) return false;

                if (dragState.dragging == null) {
                    dragState.pressedCard = null;
                    return true;
                }

                float cx = dragState.dragging.rect.centerX();
                float cy = dragState.dragging.rect.centerY();

                if (layout.boardZone.contains(cx, cy)) {
                    Card card = dragState.dragging.card;
                    RulesEngine.PlayResult result = controller.playCard(card, true);
                    if (!result.success) {
                        long now2 = android.os.SystemClock.uptimeMillis();
                        String msg;
                        if (result.failReason == RulesEngine.PlayFailReason.NO_BOARD) {
                            msg = "FIELD A PLAYER";
                        } else if (result.failReason == RulesEngine.PlayFailReason.DRIVE_USED) {
                            msg = "ONE PER TURN";
                        } else {
                            msg = "NOT ENOUGH MOMENTUM";
                        }
                        controller.showBanner(msg, now2, 900);
                    }
                }

                dragState.dragging = null;
                dragState.pressedCard = null;

                controller.requestLayoutAndInvalidate();
                return true;
        }

        return false;
    }

    private void scheduleHandLongPress(VisualCard dc) {
        cancelHandLongPress();
        dragState.longPressTriggered = false;
        dragState.longPressRunnable = () -> {
            if (dragState.pressedCard == dc && dragState.dragging == null) {
                ui.inspectCard = dc.card;
                ui.showInspect = true;
                ui.inspectFromLog = false;
                ui.inspectOpponent = false;
                dragState.longPressTriggered = true;
                dragState.pressedCard = null;
                if (tutorial != null) tutorial.onCardInspected();
                uiCallbacks.invalidate();
            }
        };
        uiCallbacks.postDelayed(dragState.longPressRunnable, 350);
    }

    private void cancelHandLongPress() {
        if (dragState.longPressRunnable != null) {
            uiCallbacks.removeCallbacks(dragState.longPressRunnable);
            dragState.longPressRunnable = null;
        }
    }

    private void scheduleBoardLongPress(VisualCard dc) {
        cancelBoardLongPress();
        dragState.boardLongPressTriggered = false;
        dragState.boardLongPressRunnable = () -> {
            if (dragState.pressedBoardCard == dc && dragState.dragging == null) {
                ui.inspectCard = dc.card;
                ui.showInspect = true;
                ui.inspectFromLog = false;
                ui.inspectOpponent = state.oppBoard.contains(dc.card);
                dragState.boardLongPressTriggered = true;
                dragState.pressedBoardCard = null;
                uiCallbacks.invalidate();
            }
        };
        uiCallbacks.postDelayed(dragState.boardLongPressRunnable, 350);
    }

    private void cancelBoardLongPress() {
        if (dragState.boardLongPressRunnable != null) {
            uiCallbacks.removeCallbacks(dragState.boardLongPressRunnable);
            dragState.boardLongPressRunnable = null;
        }
    }

    private VisualCard findBoardCardAt(float x, float y) {
        for (int i = layout.yourBoardVisual.size() - 1; i >= 0; i--) {
            VisualCard dc = layout.yourBoardVisual.get(i);
            if (dc.rect.contains(x, y)) return dc;
        }
        for (int i = layout.oppBoardVisual.size() - 1; i >= 0; i--) {
            VisualCard dc = layout.oppBoardVisual.get(i);
            if (dc.rect.contains(x, y)) return dc;
        }
        return null;
    }

    private float dp(float v) {
        return v * density;
    }
}
