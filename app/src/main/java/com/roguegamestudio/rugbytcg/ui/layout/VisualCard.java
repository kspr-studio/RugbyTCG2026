package com.roguegamestudio.rugbytcg.ui.layout;

import android.graphics.RectF;

import com.roguegamestudio.rugbytcg.Card;

public class VisualCard {
    public final Card card;
    public final RectF rect;

    public VisualCard(Card card, RectF rect) {
        this.card = card;
        this.rect = rect;
    }
}
