package com.roguegamestudio.rugbytcg.ui.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;

import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.tutorial.TutorialController;
import com.roguegamestudio.rugbytcg.ui.input.DragState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;
import com.roguegamestudio.rugbytcg.ui.layout.VisualCard;

public class HandRenderer {
    private final CardRenderer cardRenderer;

    public HandRenderer(CardRenderer cardRenderer) {
        this.cardRenderer = cardRenderer;
    }

    public void drawHand(Canvas c, RenderContext ctx, LayoutSpec layout, GameState state, DragState dragState, TutorialController tutorial, long now) {
        ctx.paint.setColor(Color.rgb(20, 20, 25));
        c.drawRect(layout.handZone, ctx.paint);

        ctx.paint.setTextSize(ctx.dp(16));
        float momentumX = layout.handZone.left + ctx.dp(12);
        float momentumY = layout.handZone.top + ctx.dp(24);
        if (tutorial != null && tutorial.isActive() && tutorial.getStep() == TutorialController.TUT_MOMENTUM_USED) {
            String prefix = "MOMENTUM: ";
            ctx.paint.setColor(Color.WHITE);
            c.drawText(prefix, momentumX, momentumY, ctx.paint);
            float prefixW = ctx.paint.measureText(prefix);
            boolean flashOn = ((now / 300L) % 2L) == 0L;
            int alpha = flashOn ? 255 : 80;
            ctx.paint.setColor(Color.argb(alpha, 255, 255, 255));
            c.drawText(String.valueOf(state.yourMomentum), momentumX + prefixW, momentumY, ctx.paint);
        } else {
            ctx.paint.setColor(Color.WHITE);
            c.drawText("MOMENTUM: " + state.yourMomentum, momentumX, momentumY, ctx.paint);
        }

        int handIndex = 0;
        for (VisualCard vc : layout.yourHandVisual) {
            if (vc == dragState.dragging) continue;
            cardRenderer.drawCardImageOnly(c, vc.rect, vc.card, false, false, false, ctx);
            if (tutorial != null && tutorial.isActive()
                    && tutorial.getStep() == TutorialController.TUT_MOMENTUM_USED && handIndex < 2) {
                float radius = getCardCornerRadius(vc.rect, ctx);
                ctx.paint.setColor(Color.argb(210, 0, 0, 0));
                c.drawRoundRect(vc.rect, radius, radius, ctx.paint);
            }
            handIndex++;
        }

        if (dragState.dragging != null) {
            cardRenderer.drawCardImageOnly(c, dragState.dragging.rect, dragState.dragging.card, false, false, false, ctx);
        }
    }

    private float getCardCornerRadius(RectF rect, RenderContext ctx) {
        float radius = rect.width() * 0.12f;
        return Math.max(ctx.dp(10), Math.min(ctx.dp(24), radius));
    }
}
