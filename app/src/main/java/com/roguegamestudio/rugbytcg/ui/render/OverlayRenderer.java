package com.roguegamestudio.rugbytcg.ui.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;
import com.roguegamestudio.rugbytcg.ui.layout.VisualCard;

public class OverlayRenderer {
    private final CardRenderer cardRenderer;

    public OverlayRenderer(CardRenderer cardRenderer) {
        this.cardRenderer = cardRenderer;
    }

    public void drawBanner(Canvas c, RenderContext ctx, LayoutSpec layout, GameState state) {
        RectF banner = new RectF(layout.boardZone.left + ctx.dp(16), layout.boardZone.centerY() - ctx.dp(32),
                layout.boardZone.right - ctx.dp(16), layout.boardZone.centerY() + ctx.dp(32));
        ctx.paint.setColor(Color.argb(210, 0, 0, 0));
        c.drawRoundRect(banner, 20, 20, ctx.paint);

        ctx.paint.setColor(Color.WHITE);
        ctx.paint.setTextSize(ctx.dp(20));
        float tw = ctx.paint.measureText(state.bannerText);
        c.drawText(state.bannerText, banner.centerX() - tw / 2f, banner.centerY() + ctx.dp(8), ctx.paint);
    }

    public void drawFlashOverlay(Canvas c, RenderContext ctx, LayoutSpec layout, UiState ui, long now) {
        if (ui.flashDurationMs <= 0) return;
        float t = (now - ui.flashStartMs) / (float) ui.flashDurationMs;
        if (t >= 1f) {
            ui.flashDurationMs = 0;
            return;
        }
        int alpha = (int) (120 * (1f - t));
        int color = Color.argb(alpha, Color.red(ui.flashColor), Color.green(ui.flashColor), Color.blue(ui.flashColor));
        ctx.paint.setColor(color);
        c.drawRoundRect(layout.boardZone, 30, 30, ctx.paint);
    }

    public void drawBurnOverlay(Canvas c, RenderContext ctx, LayoutSpec layout, UiState ui) {
        if (ui.burnFlashCard == null) return;
        ctx.paint.setColor(Color.argb(120, 0, 0, 0));
        c.drawRect(0, 0, layout.handZone.right, layout.handZone.bottom, ctx.paint);

        float maxW = layout.handZone.right * 0.60f;
        float maxH = layout.handZone.bottom * 0.50f;
        float aspect = layout.cardH / layout.cardW;
        float w = maxW;
        float h = w * aspect;
        if (h > maxH) {
            h = maxH;
            w = h / aspect;
        }
        float left = (layout.handZone.right - w) / 2f;
        float top = (layout.handZone.bottom - h) / 2f;
        RectF r = new RectF(left, top, left + w, top + h);
        cardRenderer.drawCardImageOnly(c, r, ui.burnFlashCard, true, false, false, ctx);

        ctx.paint.setColor(Color.WHITE);
        ctx.paint.setTextSize(ctx.dp(16));
        String label = (ui.burnFlashLabel == null || ui.burnFlashLabel.trim().isEmpty())
                ? "BURNING RANDOM CARD"
                : ui.burnFlashLabel.trim().toUpperCase();
        float tw = ctx.paint.measureText(label);
        c.drawText(label, r.centerX() - tw / 2f, r.top - ctx.dp(12), ctx.paint);
    }

    public void drawInspectOverlay(Canvas c, RenderContext ctx, LayoutSpec layout, UiState ui) {
        if (ui.inspectCard == null) return;
        ctx.paint.setColor(Color.argb(180, 0, 0, 0));
        c.drawRect(0, 0, layout.handZone.right, layout.handZone.bottom, ctx.paint);

        float maxW = layout.handZone.right * 0.72f;
        float maxH = layout.handZone.bottom * 0.68f;
        float aspect = layout.cardH / layout.cardW;
        float w = maxW;
        float h = w * aspect;
        if (h > maxH) {
            h = maxH;
            w = h / aspect;
        }
        float left = (layout.handZone.right - w) / 2f;
        float top = (layout.handZone.bottom - h) / 2f;
        RectF r = new RectF(left, top, left + w, top + h);
        cardRenderer.drawCardImageOnly(c, r, ui.inspectCard, true, ui.inspectOpponent, false, ctx);
    }

    public void drawPlayedCardOverlay(Canvas c, RenderContext ctx, LayoutSpec layout, UiState ui, long now) {
        if (ui.playFlashCard == null) return;
        ctx.paint.setColor(Color.argb(120, 0, 0, 0));
        c.drawRect(0, 0, layout.handZone.right, layout.handZone.bottom, ctx.paint);

        float maxW = layout.boardZone.width() * 0.70f;
        float maxH = layout.boardZone.height() * 0.70f;
        float aspect = layout.cardH / layout.cardW;
        float w = maxW;
        float h = w * aspect;
        if (h > maxH) {
            h = maxH;
            w = h / aspect;
        }
        float left = layout.boardZone.centerX() - w / 2f;
        float top = layout.boardZone.centerY() - h / 2f;
        RectF startRect = new RectF(left, top, left + w, top + h);

        if (!ui.playFlashHasTarget && ui.playFlashSourceCard != null && ui.playFlashCard instanceof PlayerCard) {
            RectF target = findBoardRectForCard(layout, ui.playFlashSourceCard, !ui.playFlashOpponent);
            if (target != null) {
                ui.playFlashTargetRect.set(target);
                ui.playFlashHasTarget = true;
            }
        }

        RectF r = startRect;
        if (ui.playFlashHasTarget) {
            float t = (now - (ui.playFlashStartMs + ui.playFlashHoldMs)) / (float) ui.playFlashAnimMs;
            t = Math.max(0f, Math.min(1f, t));
            float ease = 1f - (1f - t) * (1f - t);
            float l = lerp(startRect.left, ui.playFlashTargetRect.left, ease);
            float tt = lerp(startRect.top, ui.playFlashTargetRect.top, ease);
            float rr = lerp(startRect.right, ui.playFlashTargetRect.right, ease);
            float bb = lerp(startRect.bottom, ui.playFlashTargetRect.bottom, ease);
            r = new RectF(l, tt, rr, bb);
        }

        cardRenderer.drawCardImageOnly(c, r, ui.playFlashCard, true, ui.playFlashOpponent, false, ctx);
        float radius = r.width() * 0.12f;
        ctx.paint.setStyle(android.graphics.Paint.Style.STROKE);
        ctx.paint.setStrokeWidth(ctx.dp(3));
        ctx.paint.setColor(ui.playFlashOpponent ? Color.rgb(220, 70, 70) : Color.rgb(70, 200, 120));
        c.drawRoundRect(r, radius, radius, ctx.paint);
        ctx.paint.setStyle(android.graphics.Paint.Style.FILL);
    }

    private RectF findBoardRectForCard(LayoutSpec layout, Card card, boolean forYou) {
        if (card == null) return null;
        java.util.List<VisualCard> list = forYou ? layout.yourBoardVisual : layout.oppBoardVisual;
        for (VisualCard vc : list) {
            if (vc.card == card) return vc.rect;
        }
        return null;
    }

    private float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
