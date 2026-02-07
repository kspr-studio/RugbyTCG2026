package com.roguegamestudio.rugbytcg.ui.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Path;
import android.graphics.RectF;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.core.GameState;

import java.util.HashSet;
import java.util.Set;

public class CardRenderer {

    public void pruneStaminaCache(RenderContext ctx, GameState state) {
        Set<PlayerCard> alive = new HashSet<>();
        for (Card c : state.yourHand) {
            if (c instanceof PlayerCard) alive.add((PlayerCard) c);
        }
        for (Card c : state.oppHand) {
            if (c instanceof PlayerCard) alive.add((PlayerCard) c);
        }
        alive.addAll(state.yourBoard);
        alive.addAll(state.oppBoard);
        ctx.staVis.keySet().removeIf(k -> !alive.contains(k));
    }

    public void drawCardImageOnly(Canvas c, RectF r, Card card, boolean fullscreen, boolean opponent, boolean showStaminaBar, RenderContext ctx) {
        boolean opponentPlayer = opponent && card instanceof PlayerCard;
        Bitmap bmp = ctx.cardArtCache.get(card.id, fullscreen, opponentPlayer);
        float radius = getCardCornerRadius(r, ctx);
        boolean isPlayer = card instanceof PlayerCard;
        float staminaRatio = 1f;
        if (isPlayer) {
            PlayerCard pc = (PlayerCard) card;
            float target = staminaTargetRatio(pc);
            staminaRatio = staminaVisualRatio(pc, target, ctx);
            staminaRatio = Math.max(0f, Math.min(1f, staminaRatio));
        }

        if (bmp == null) {
            int baseColor = opponent ? Color.rgb(170, 200, 185) : Color.rgb(150, 210, 180);
            ctx.paint.setColor(baseColor);
            c.drawRoundRect(r, radius, radius, ctx.paint);
            if (isPlayer && showStaminaBar) {
                PlayerCard pc = (PlayerCard) card;
                boolean isFull = pc.staCurrent >= pc.staMax;
                boolean isLastTurn = pc.staCurrent == 1;
                drawStaminaBar(c, r, staminaRatio, isFull, isLastTurn, ctx);
            }
            return;
        }

        c.save();
        Path path = new Path();
        path.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(path);
        if (isPlayer) {
            ColorMatrix cm = new ColorMatrix();
            cm.setSaturation(staminaRatio);
            ctx.imagePaint.setColorFilter(new ColorMatrixColorFilter(cm));
        } else {
            ctx.imagePaint.setColorFilter(null);
        }
        drawBitmapCenterCrop(c, bmp, r, ctx);
        ctx.imagePaint.setColorFilter(null);
        if (isPlayer && showStaminaBar) {
            PlayerCard pc = (PlayerCard) card;
            boolean isFull = pc.staCurrent >= pc.staMax;
            boolean isLastTurn = pc.staCurrent == 1;
            drawStaminaBar(c, r, staminaRatio, isFull, isLastTurn, ctx);
        }
        if (isPlayer) {
            PlayerCard pc = (PlayerCard) card;
            if (pc.staCurrent == 1) {
                float pulse = (float) (0.5 + 0.5 * Math.sin(android.os.SystemClock.uptimeMillis() / 300.0));
                int alpha = 40 + Math.round(60 * pulse);
                ctx.paint.setColor(Color.argb(alpha, 220, 60, 60));
                c.drawRect(r, ctx.paint);
            }
        }
        c.restore();
    }

    public void drawCardImageOnlyLog(Canvas c, RectF r, Card card, boolean opponent, float radius, RenderContext ctx) {
        Bitmap bmp = ctx.cardArtCache.get(card.id, false, false);
        if (bmp == null) {
            int baseColor = opponent ? Color.rgb(170, 200, 185) : Color.rgb(150, 210, 180);
            ctx.paint.setColor(baseColor);
            c.drawRoundRect(r, radius, radius, ctx.paint);
            return;
        }
        c.save();
        Path path = new Path();
        path.addRoundRect(r, radius, radius, Path.Direction.CW);
        c.clipPath(path);
        drawBitmapCenterCrop(c, bmp, r, ctx);
        c.restore();
    }

    private void drawStaminaBar(Canvas c, RectF r, float ratio, boolean isFull, boolean isLastTurn, RenderContext ctx) {
        float barH = ctx.dp(6);
        RectF bar = new RectF(r.left, r.top, r.right, r.top + barH);
        ctx.staBackPaint.setColor(Color.argb(140, 0, 0, 0));
        c.drawRect(bar, ctx.staBackPaint);
        float fillRight = r.left + (r.width() * ratio);
        RectF fill = new RectF(bar.left, bar.top, fillRight, bar.bottom);
        int color;
        if (isLastTurn) {
            color = Color.rgb(210, 60, 60);
        } else if (isFull) {
            color = Color.rgb(70, 200, 120);
        } else {
            color = Color.rgb(230, 190, 60);
        }
        ctx.staFillPaint.setColor(color);
        c.drawRect(fill, ctx.staFillPaint);
    }

    private float staminaTargetRatio(PlayerCard pc) {
        int max = pc.staMax;
        if (max <= 0) return 0f;
        return Math.max(0f, Math.min(1f, pc.staCurrent / (float) max));
    }

    private float staminaVisualRatio(PlayerCard pc, float target, RenderContext ctx) {
        float cur = ctx.staVis.containsKey(pc) ? ctx.staVis.get(pc) : target;
        float speed = 8f;
        float maxStep = speed * ctx.frameDtSeconds;
        float next;
        if (cur < target) next = Math.min(target, cur + maxStep);
        else next = Math.max(target, cur - maxStep);
        ctx.staVis.put(pc, next);
        return next;
    }

    private void drawBitmapCenterCrop(Canvas c, Bitmap bmp, RectF dst, RenderContext ctx) {
        int bw = bmp.getWidth();
        int bh = bmp.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float dstW = dst.width();
        float dstH = dst.height();
        float scale = Math.max(dstW / bw, dstH / bh);
        float scaledW = bw * scale;
        float scaledH = bh * scale;
        float left = dst.left + (dstW - scaledW) / 2f;
        float top  = dst.top + (dstH - scaledH) / 2f;
        RectF scaledRect = new RectF(left, top, left + scaledW, top + scaledH);
        c.drawBitmap(bmp, null, scaledRect, ctx.imagePaint);
    }

    private float getCardCornerRadius(RectF r, RenderContext ctx) {
        float radius = r.width() * 0.12f;
        return Math.max(ctx.dp(10), Math.min(ctx.dp(24), radius));
    }
}
