package com.roguegamestudio.rugbytcg.ui.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.roguegamestudio.rugbytcg.core.LogEntry;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.ui.log.LogLayoutHelper;

public class LogRenderer {
    private final CardRenderer cardRenderer;

    public LogRenderer(CardRenderer cardRenderer) {
        this.cardRenderer = cardRenderer;
    }

    public void drawLogOverlay(Canvas c, RenderContext ctx, UiState ui, float screenW, float screenH, float cardW, float cardH) {
        LogLayoutHelper.ensureLayout(ui, screenW, screenH, ctx.dp(1));

        ctx.paint.setColor(Color.argb(160, 0, 0, 0));
        c.drawRect(0, 0, screenW, screenH, ctx.paint);

        ctx.paint.setColor(Color.rgb(30, 30, 35));
        c.drawRoundRect(ui.logPanel, ctx.dp(18), ctx.dp(18), ctx.paint);

        ctx.paint.setColor(Color.WHITE);
        ctx.paint.setTextSize(ctx.dp(16));
        String title = "PLAY LOG";
        float tw = ctx.paint.measureText(title);
        c.drawText(title, ui.logPanel.centerX() - tw / 2f, ui.logPanel.top + ctx.dp(24), ctx.paint);

        ctx.paint.setTextSize(ctx.dp(14));
        Paint.FontMetrics fm = ctx.paint.getFontMetrics();
        float lineGap = ctx.dp(10);
        float thumbW = cardW * 0.5f;
        float thumbH = cardH * 0.5f;
        float thumbRadius = ctx.dp(10);

        c.save();
        c.clipRect(ui.logListArea);

        float y = ui.logListArea.top - ui.logScrollY;
        for (LogEntry entry : ui.playLog) {
            float rowTop = y;
            float rowBottom = y + thumbH;
            if (rowBottom >= ui.logListArea.top && rowTop <= ui.logListArea.bottom) {
                float timeX = ui.logListArea.left;
                float timeBaseline = rowTop + (thumbH / 2f) - (fm.ascent + fm.descent) / 2f;
                ctx.paint.setColor(Color.WHITE);
                c.drawText(entry.time, timeX, timeBaseline, ctx.paint);

                float timeW = ctx.paint.measureText(entry.time);
                float cardLeft = timeX + timeW + ctx.dp(10);
                RectF thumb = new RectF(cardLeft, rowTop, cardLeft + thumbW, rowTop + thumbH);
                cardRenderer.drawCardImageOnlyLog(c, thumb, entry.snapshot.card, entry.opponent, thumbRadius, ctx);

                ctx.paint.setStyle(Paint.Style.STROKE);
                ctx.paint.setStrokeWidth(ctx.dp(2));
                ctx.paint.setColor(entry.opponent ? Color.rgb(220, 80, 80) : Color.rgb(80, 200, 120));
                c.drawRoundRect(thumb, thumbRadius, thumbRadius, ctx.paint);
                ctx.paint.setStyle(Paint.Style.FILL);

                ctx.paint.setColor(Color.WHITE);
                float textX = thumb.right + ctx.dp(10);
                ctx.paint.setTextSize(ctx.dp(13));
                Paint.FontMetrics fmSmall = ctx.paint.getFontMetrics();
                float lineH = fmSmall.descent - fmSmall.ascent;
                String yourLabel = shortLabel(ui.localPlayerLabel, "HOME");
                String oppLabel = shortLabel(ui.opponentPlayerLabel, "AWAY");
                String whoLine = entry.opponent ? (oppLabel + " PLAYED:") : (yourLabel + " PLAYED:");
                c.drawText(whoLine, textX, rowTop + lineH, ctx.paint);
                String name = entry.snapshot.card != null && entry.snapshot.card.name != null
                        ? entry.snapshot.card.name.toUpperCase()
                        : "CARD";
                c.drawText(name, textX, rowTop + lineH * 2f, ctx.paint);
                ctx.paint.setTextSize(ctx.dp(14));
            }
            y += thumbH + lineGap;
        }

        c.restore();
    }

    private String shortLabel(String value, String fallback) {
        String src = value == null ? "" : value.trim();
        if (src.isEmpty()) src = fallback;
        if (src.length() <= 10) return src.toUpperCase();
        return src.substring(0, 10).toUpperCase();
    }
}
