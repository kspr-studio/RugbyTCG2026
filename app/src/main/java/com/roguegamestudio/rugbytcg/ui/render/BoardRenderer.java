package com.roguegamestudio.rugbytcg.ui.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;
import com.roguegamestudio.rugbytcg.ui.layout.VisualCard;

public class BoardRenderer {
    private final CardRenderer cardRenderer;

    public BoardRenderer(CardRenderer cardRenderer) {
        this.cardRenderer = cardRenderer;
    }

    public void drawBoard(Canvas c, RenderContext ctx, LayoutSpec layout, GameState state, UiState ui, long now) {
        if (ctx.textureCache.getFieldTexture() != null) {
            Path p = new Path();
            p.addRoundRect(layout.boardZone, 30, 30, Path.Direction.CW);
            int save = c.save();
            c.clipPath(p);
            drawBitmapCenterCrop(c, ctx.textureCache.getFieldTexture(), layout.boardZone, ctx);
            c.restoreToCount(save);
        } else {
            ctx.paint.setColor(Color.rgb(25, 60, 40));
            c.drawRoundRect(layout.boardZone, 30, 30, ctx.paint);
        }

        for (VisualCard vc : layout.oppBoardVisual) {
            if (shouldHideBoardCard(vc.card, ui, now)) continue;
            cardRenderer.drawCardImageOnly(c, vc.rect, vc.card, false, true, true, ctx);
        }

        for (VisualCard vc : layout.yourBoardVisual) {
            if (shouldHideBoardCard(vc.card, ui, now)) continue;
            cardRenderer.drawCardImageOnly(c, vc.rect, vc.card, false, false, true, ctx);
        }
    }

    private boolean shouldHideBoardCard(Card card, UiState ui, long now) {
        if (card == null) return false;
        if (ui.playFlashSourceCard == null || ui.playFlashSourceCard != card) return false;
        return now < (ui.playFlashStartMs + ui.playFlashHoldMs + ui.playFlashAnimMs);
    }

    private void drawBitmapCenterCrop(Canvas c, android.graphics.Bitmap bmp, RectF dst, RenderContext ctx) {
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
}
