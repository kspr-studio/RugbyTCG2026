package com.roguegamestudio.rugbytcg.ui.input;

import com.roguegamestudio.rugbytcg.ui.layout.VisualCard;

public class DragState {
    public float downX = 0f;
    public float downY = 0f;
    public float grabDx = 0f;
    public float grabDy = 0f;

    public VisualCard dragging = null;
    public VisualCard pressedCard = null;

    public boolean longPressTriggered = false;
    public Runnable longPressRunnable = null;

    public VisualCard pressedBoardCard = null;
    public boolean boardLongPressTriggered = false;
    public Runnable boardLongPressRunnable = null;
    public float boardDownX = 0f;
    public float boardDownY = 0f;
}
