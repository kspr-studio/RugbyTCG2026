package com.roguegamestudio.rugbytcg.ui.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;

import com.roguegamestudio.rugbytcg.tutorial.TutorialController;
import com.roguegamestudio.rugbytcg.ui.input.DragState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;

public class TutorialRenderer {
    public void drawTutorialOverlay(Canvas c, RenderContext ctx, LayoutSpec layout, TutorialController tutorial, DragState dragState) {
        if (tutorial == null || !tutorial.isActive()) return;
        RectF target = tutorial.getHighlightRect(layout, dragState, ctx.dp(1));
        ctx.paint.setColor(Color.argb(240, 0, 0, 0));

        if (tutorial.getStep() == TutorialController.TUT_PLAY_FLANKER && dragState.dragging != null) {
            Path p = new Path();
            p.addRect(0, 0, layout.handZone.right, layout.handZone.bottom, Path.Direction.CW);
            p.addRoundRect(layout.boardZone, ctx.dp(30), ctx.dp(30), Path.Direction.CW);
            p.setFillType(Path.FillType.EVEN_ODD);
            c.drawPath(p, ctx.paint);
        } else if (tutorial.getStep() == TutorialController.TUT_INTRO) {
            Path p = new Path();
            p.addRect(0, 0, layout.handZone.right, layout.handZone.bottom, Path.Direction.CW);
            p.addRoundRect(layout.scoreZone, ctx.dp(30), ctx.dp(30), Path.Direction.CW);
            p.setFillType(Path.FillType.EVEN_ODD);
            c.drawPath(p, ctx.paint);
        } else if (tutorial.getStep() == TutorialController.TUT_ENDTURN) {
            Path p = new Path();
            p.addRect(0, 0, layout.handZone.right, layout.handZone.bottom, Path.Direction.CW);
            p.addRoundRect(layout.endTurnBtn, ctx.dp(14), ctx.dp(14), Path.Direction.CW);
            p.setFillType(Path.FillType.EVEN_ODD);
            c.drawPath(p, ctx.paint);
        } else if (tutorial.getStep() == TutorialController.TUT_LOG) {
            Path p = new Path();
            p.addRect(0, 0, layout.handZone.right, layout.handZone.bottom, Path.Direction.CW);
            p.addRoundRect(layout.logBtn, ctx.dp(12), ctx.dp(12), Path.Direction.CW);
            p.setFillType(Path.FillType.EVEN_ODD);
            c.drawPath(p, ctx.paint);
        } else if (tutorial.getStep() == TutorialController.TUT_HAND_MOMENTUM
                || tutorial.getStep() == TutorialController.TUT_HAND_COSTS
                || tutorial.getStep() == TutorialController.TUT_HAND_STAMINA
                || tutorial.getStep() == TutorialController.TUT_HAND_POWER
                || tutorial.getStep() == TutorialController.TUT_MOMENTUM_USED
                || tutorial.getStep() == TutorialController.TUT_TACTIC_REQ
                || (tutorial.getStep() == TutorialController.TUT_INSPECT && dragState.dragging == null)) {
            RectF handSpot = tutorial.getTutorialHandZoneRect(layout, ctx.dp(1));
            Path p = new Path();
            p.addRect(0, 0, layout.handZone.right, layout.handZone.bottom, Path.Direction.CW);
            p.addRoundRect(handSpot, ctx.dp(30), ctx.dp(30), Path.Direction.CW);
            p.setFillType(Path.FillType.EVEN_ODD);
            c.drawPath(p, ctx.paint);
        } else if (target != null) {
            float radius = Math.max(target.width(), target.height()) * 0.6f + ctx.dp(18);
            if (tutorial.getStep() == TutorialController.TUT_SCORE
                    || tutorial.getStep() == TutorialController.TUT_MATCH_TIMER
                    || tutorial.getStep() == TutorialController.TUT_ROUND_TIMER
                    || tutorial.getStep() == TutorialController.TUT_PHASE_TOTALS) {
                radius *= 0.5f;
            }
            if (tutorial.getStep() == TutorialController.TUT_BALL) {
                radius *= 0.9f;
            }
            Path p = new Path();
            p.addRect(0, 0, layout.handZone.right, layout.handZone.bottom, Path.Direction.CW);
            p.addCircle(target.centerX(), target.centerY(), radius, Path.Direction.CW);
            p.setFillType(Path.FillType.EVEN_ODD);
            c.drawPath(p, ctx.paint);
        } else {
            c.drawRect(0, 0, layout.handZone.right, layout.handZone.bottom, ctx.paint);
        }

        if (tutorial.getStep() == TutorialController.TUT_INTRO) {
            float pulse = (float) (0.5 + 0.5 * Math.sin(android.os.SystemClock.uptimeMillis() / 250.0));
            int alpha = 80 + Math.round(175 * pulse);
            ctx.paint.setStyle(android.graphics.Paint.Style.STROKE);
            ctx.paint.setStrokeWidth(ctx.dp(3));
            ctx.paint.setColor(Color.argb(alpha, 255, 255, 255));
            c.drawRoundRect(layout.scoreZone, ctx.dp(30), ctx.dp(30), ctx.paint);
            ctx.paint.setStyle(android.graphics.Paint.Style.FILL);
        }

        float boxW = layout.handZone.right * 0.86f;
        float boxH = ctx.dp(110);
        float boxLeft = (layout.handZone.right - boxW) / 2f;
        float boxTop = (layout.handZone.bottom - boxH) / 2f;
        tutorial.tutorialTextBox.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH);

        ctx.paint.setColor(Color.argb(230, 20, 20, 25));
        c.drawRoundRect(tutorial.tutorialTextBox, ctx.dp(16), ctx.dp(16), ctx.paint);

        ctx.paint.setColor(Color.WHITE);
        ctx.paint.setTextSize(ctx.dp(14));
        float textX = tutorial.tutorialTextBox.left + ctx.dp(12);
        float textY = tutorial.tutorialTextBox.top + ctx.dp(10);
        float textW = tutorial.tutorialTextBox.width() - ctx.dp(24);
        drawWrappedText(c, ctx, tutorial.getTutorialText(), textX, textY, textW, 3);

        boolean showNext = (tutorial.getStep() == TutorialController.TUT_INTRO
                || tutorial.getStep() == TutorialController.TUT_SCORE
                || tutorial.getStep() == TutorialController.TUT_MATCH_TIMER
                || tutorial.getStep() == TutorialController.TUT_ROUND_TIMER
                || tutorial.getStep() == TutorialController.TUT_PHASE_TOTALS
                || tutorial.getStep() == TutorialController.TUT_BALL
                || tutorial.getStep() == TutorialController.TUT_HAND_MOMENTUM
                || tutorial.getStep() == TutorialController.TUT_HAND_COSTS
                || tutorial.getStep() == TutorialController.TUT_HAND_STAMINA
                || tutorial.getStep() == TutorialController.TUT_HAND_POWER
                || tutorial.getStep() == TutorialController.TUT_MOMENTUM_USED
                || tutorial.getStep() == TutorialController.TUT_TACTIC_REQ
                || tutorial.getStep() == TutorialController.TUT_FINISH);

        float btnH = ctx.dp(28);
        float btnW = ctx.dp(90);
        float btnY = tutorial.tutorialTextBox.bottom - btnH - ctx.dp(8);

        tutorial.tutorialSkipBtn.setEmpty();
        tutorial.tutorialStartBtn.setEmpty();

        if (showNext) {
            String label = (tutorial.getStep() == TutorialController.TUT_FINISH) ? "MENU" : "NEXT";
            tutorial.tutorialNextBtn.set(tutorial.tutorialTextBox.right - ctx.dp(10) - btnW, btnY,
                    tutorial.tutorialTextBox.right - ctx.dp(10), btnY + btnH);
            if (tutorial.getStep() == TutorialController.TUT_FINISH) {
                float startW = ctx.dp(150);
                tutorial.tutorialStartBtn.set(tutorial.tutorialTextBox.left + ctx.dp(10), btnY,
                        tutorial.tutorialTextBox.left + ctx.dp(10) + startW, btnY + btnH);
            }
            ctx.paint.setColor(Color.rgb(230, 230, 240));
            c.drawRoundRect(tutorial.tutorialNextBtn, ctx.dp(10), ctx.dp(10), ctx.paint);
            ctx.paint.setColor(Color.BLACK);
            ctx.paint.setTextSize(ctx.dp(12));
            float tw = ctx.paint.measureText(label);
            c.drawText(label, tutorial.tutorialNextBtn.centerX() - tw / 2f, tutorial.tutorialNextBtn.centerY() + ctx.dp(4), ctx.paint);
            if (tutorial.getStep() == TutorialController.TUT_FINISH && !tutorial.tutorialStartBtn.isEmpty()) {
                ctx.paint.setColor(Color.rgb(230, 230, 240));
                c.drawRoundRect(tutorial.tutorialStartBtn, ctx.dp(10), ctx.dp(10), ctx.paint);
                ctx.paint.setColor(Color.BLACK);
                String startLabel = "START BOT MATCH";
                float twStart = ctx.paint.measureText(startLabel);
                c.drawText(startLabel, tutorial.tutorialStartBtn.centerX() - twStart / 2f, tutorial.tutorialStartBtn.centerY() + ctx.dp(4), ctx.paint);
            }
        } else {
            tutorial.tutorialNextBtn.setEmpty();
        }

        if (tutorial.getStep() == TutorialController.TUT_FINISH) {
            ctx.paint.setStyle(android.graphics.Paint.Style.STROKE);
            ctx.paint.setStrokeWidth(ctx.dp(2));
            ctx.paint.setColor(Color.WHITE);
            if (!tutorial.tutorialNextBtn.isEmpty()) {
                c.drawRoundRect(tutorial.tutorialNextBtn, ctx.dp(10), ctx.dp(10), ctx.paint);
            }
            if (!tutorial.tutorialStartBtn.isEmpty()) {
                c.drawRoundRect(tutorial.tutorialStartBtn, ctx.dp(10), ctx.dp(10), ctx.paint);
            }
            ctx.paint.setStyle(android.graphics.Paint.Style.FILL);
        }
    }

    private int drawWrappedText(Canvas c, RenderContext ctx, String text, float x, float yTop, float maxWidth, int maxLines) {
        if (text == null) return 0;
        String remaining = text.trim();
        if (remaining.isEmpty()) return 0;

        android.graphics.Paint.FontMetrics fm = ctx.paint.getFontMetrics();
        float lineHeight = (fm.descent - fm.ascent);
        float lineGap = ctx.dp(2);
        int lines = 0;

        while (!remaining.isEmpty() && lines < maxLines) {
            int count = ctx.paint.breakText(remaining, true, maxWidth, null);
            if (count <= 0) break;

            int end = count;
            if (count < remaining.length()) {
                int space = remaining.lastIndexOf(' ', count);
                if (space > 0) end = space;
            }

            String line = remaining.substring(0, end).trim();
            remaining = remaining.substring(end).trim();

            if (lines == maxLines - 1 && !remaining.isEmpty()) {
                line = ellipsizeToWidth(ctx, line, maxWidth);
            }

            float baseline = yTop - fm.ascent + lines * (lineHeight + lineGap);
            c.drawText(line, x, baseline, ctx.paint);
            lines++;
        }
        return lines;
    }

    private String ellipsizeToWidth(RenderContext ctx, String text, float maxWidth) {
        String ellipsis = "...";
        if (ctx.paint.measureText(text) <= maxWidth) return text;
        float ellipsisW = ctx.paint.measureText(ellipsis);
        int count = ctx.paint.breakText(text, true, Math.max(0, maxWidth - ellipsisW), null);
        if (count <= 0) return ellipsis;
        return text.substring(0, count).trim() + ellipsis;
    }
}
