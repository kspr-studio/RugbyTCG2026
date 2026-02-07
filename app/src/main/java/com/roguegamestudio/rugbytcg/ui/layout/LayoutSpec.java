package com.roguegamestudio.rugbytcg.ui.layout;

import android.graphics.RectF;

import java.util.ArrayList;
import java.util.List;

public class LayoutSpec {
    public final RectF scoreZone = new RectF();
    public final RectF turnZone = new RectF();
    public final RectF statsZone = new RectF();
    public final RectF statsTableArea = new RectF();
    public final RectF statsHudArea = new RectF();
    public final RectF boardZone = new RectF();
    public final RectF handZone = new RectF();
    public final RectF endTurnBtn = new RectF();
    public final RectF logBtn = new RectF();
    public final RectF menuBtn = new RectF();

    public final RectF oppHandArea = new RectF();
    public final List<RectF> oppHandBackRects = new ArrayList<>();

    public float cardW;
    public float cardH;
    public float handCardW;
    public float handCardH;
    public float oppBoardStackHeight;

    public final List<VisualCard> yourHandVisual = new ArrayList<>();
    public final List<VisualCard> yourBoardVisual = new ArrayList<>();
    public final List<VisualCard> oppBoardVisual = new ArrayList<>();
}
