package com.roguegamestudio.rugbytcg.ui.render;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.engine.MatchEngine;
import com.roguegamestudio.rugbytcg.engine.RulesEngine;
import com.roguegamestudio.rugbytcg.engine.TurnEngine;
import com.roguegamestudio.rugbytcg.tutorial.TutorialController;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;

public class HudRenderer {
    public void drawScoreBar(Canvas c,
                             RenderContext ctx,
                             LayoutSpec layout,
                             GameState state,
                             UiState ui,
                             MatchEngine matchEngine,
                             TutorialController tutorial) {
        ctx.paint.setColor(Color.argb(210, 0, 0, 0));
        c.drawRoundRect(layout.scoreZone, ctx.dp(18), ctx.dp(18), ctx.paint);

        float pad = ctx.dp(12);
        float baseline = layout.scoreZone.centerY() + ctx.dp(6);

        ctx.paint.setTextSize(ctx.dp(14));
        ctx.paint.setFakeBoldText(false);
        String leftLabel = shortLabel(ui != null ? ui.localPlayerLabel : null, "HOME");
        String rightLabel = shortLabel(ui != null ? ui.opponentPlayerLabel : null, "AWAY");
        String leftText = leftLabel + " " + state.homeScore;
        ctx.paint.setColor(Color.rgb(70, 200, 120));
        c.drawText(leftText, layout.scoreZone.left + pad, baseline, ctx.paint);

        String rightText = state.awayScore + " " + rightLabel;
        float rw = ctx.paint.measureText(rightText);
        ctx.paint.setColor(Color.rgb(220, 70, 70));
        c.drawText(rightText, layout.scoreZone.right - pad - rw, baseline, ctx.paint);

        boolean tutorialActive = tutorial != null && tutorial.isActive();
        String centerText = state.matchOver ? "FINAL" : formatMmSs(tutorialActive ? 0L : matchEngine.getMatchElapsedMs(state));
        ctx.paint.setColor(Color.WHITE);
        ctx.paint.setTextSize(ctx.dp(16));
        ctx.paint.setFakeBoldText(true);
        float tw = ctx.paint.measureText(centerText);
        c.drawText(centerText, layout.scoreZone.centerX() - tw / 2f, baseline, ctx.paint);
        ctx.paint.setFakeBoldText(false);
    }

    public void drawTurnBar(Canvas c, RenderContext ctx, LayoutSpec layout, TurnEngine turnEngine, TutorialController tutorial) {
        ctx.paint.setColor(Color.argb(200, 0, 0, 0));
        c.drawRoundRect(layout.turnZone, ctx.dp(16), ctx.dp(16), ctx.paint);

        boolean logEnabled = tutorial == null || tutorial.isLogEnabled();
        ctx.paint.setColor(logEnabled ? Color.rgb(230, 230, 240) : Color.rgb(120, 120, 130));
        c.drawRoundRect(layout.logBtn, ctx.dp(12), ctx.dp(12), ctx.paint);
        ctx.paint.setColor(logEnabled ? Color.BLACK : Color.DKGRAY);
        ctx.paint.setTextSize(ctx.dp(14));
        float tw = ctx.paint.measureText("LOG");
        c.drawText("LOG", layout.logBtn.centerX() - tw / 2f, layout.logBtn.centerY() + ctx.dp(5), ctx.paint);

        boolean menuEnabled = tutorial == null || !tutorial.isActive();
        ctx.paint.setColor(menuEnabled ? Color.rgb(230, 230, 240) : Color.rgb(120, 120, 130));
        c.drawRoundRect(layout.menuBtn, ctx.dp(12), ctx.dp(12), ctx.paint);
        ctx.paint.setColor(menuEnabled ? Color.BLACK : Color.DKGRAY);
        ctx.paint.setTextSize(ctx.dp(14));
        float mw = ctx.paint.measureText("MENU");
        c.drawText("MENU", layout.menuBtn.centerX() - mw / 2f, layout.menuBtn.centerY() + ctx.dp(5), ctx.paint);

        boolean tutorialActive = tutorial != null && tutorial.isActive();
        long remaining = tutorialActive ? 0L : Math.max(0L, turnEngine.getTurnDisplayRemainingMs());
        String timer = formatMmSs(remaining);
        String text = timer;
        boolean localTurn = turnEngine.getTurnState() == TurnEngine.TurnState.PLAYER;
        ctx.paint.setColor(localTurn ? Color.rgb(70, 200, 120) : Color.rgb(220, 70, 70));
        ctx.paint.setTextSize(ctx.dp(14));
        float tw2 = ctx.paint.measureText(text);
        c.drawText(text, layout.turnZone.right - ctx.dp(10) - tw2, layout.turnZone.centerY() + ctx.dp(5), ctx.paint);
    }

    public void drawStatsBar(Canvas c,
                             RenderContext ctx,
                             LayoutSpec layout,
                             GameState state,
                             UiState ui,
                             RulesEngine rules) {
        ctx.paint.setColor(Color.argb(200, 0, 0, 0));
        c.drawRoundRect(layout.statsZone, ctx.dp(16), ctx.dp(16), ctx.paint);

        ctx.paint.setTextSize(ctx.dp(12));
        Paint.FontMetrics fm = ctx.paint.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float gap = ctx.dp(2);
        float y = layout.statsTableArea.top;
        float x = layout.statsTableArea.left;

        int yourTotal = rules.computeSideTotal(state, true);
        int oppTotal = rules.computeSideTotal(state, false);
        String yourLabel = shortLabel(ui != null ? ui.localPlayerLabel : null, "HOME") + ":";
        String oppLabel = shortLabel(ui != null ? ui.opponentPlayerLabel : null, "AWAY") + ":";
        float labelW = Math.max(ctx.paint.measureText(yourLabel), ctx.paint.measureText(oppLabel));
        float valX = x + labelW + ctx.dp(8);

        ctx.paint.setColor(Color.rgb(70, 200, 120));
        c.drawText(yourLabel, x, y - fm.ascent, ctx.paint);
        c.drawText(String.valueOf(yourTotal), valX, y - fm.ascent, ctx.paint);
        y += lineH + gap;

        ctx.paint.setColor(Color.rgb(220, 70, 70));
        c.drawText(oppLabel, x, y - fm.ascent, ctx.paint);
        c.drawText(String.valueOf(oppTotal), valX, y - fm.ascent, ctx.paint);

        ctx.paint.setColor(Color.WHITE);
        ctx.paint.setTextSize(ctx.dp(14));
        String ballText = String.valueOf(state.ballPos);
        float tw = ctx.paint.measureText(ballText);
        c.drawText(ballText, layout.statsZone.centerX() - tw / 2f, layout.statsZone.centerY() + ctx.dp(5), ctx.paint);
    }

    public void drawEndTurnButton(Canvas c,
                                  RenderContext ctx,
                                  LayoutSpec layout,
                                  GameState state,
                                  UiState ui,
                                  TurnEngine turnEngine,
                                  TutorialController tutorial) {
        boolean kickoffPending = ui != null && ui.onlineInitialKickoffPending;
        boolean kickoffWaiting = ui != null && ui.onlineKickoffWaiting;
        boolean enabled = kickoffPending
                ? !kickoffWaiting
                : (state.matchOver || (turnEngine.getTurnState() == TurnEngine.TurnState.PLAYER));
        if (!kickoffPending && tutorial != null && tutorial.isActive() && !tutorial.isEndTurnEnabled()) {
            enabled = false;
        }

        ctx.paint.setColor(enabled ? Color.rgb(230, 230, 240) : Color.rgb(170, 170, 180));
        c.drawRoundRect(layout.endTurnBtn, 22, 22, ctx.paint);

        ctx.paint.setColor(Color.BLACK);
        ctx.paint.setTextSize(ctx.dp(18));
        String t;
        if (state.matchOver) t = "NEW MATCH";
        else if (kickoffPending) t = kickoffWaiting ? "WAIT..." : "KICKOFF";
        else t = (turnEngine.getTurnState() == TurnEngine.TurnState.AI_THINKING) ? "WAIT..." : "END TURN";
        float tw = ctx.paint.measureText(t);
        c.drawText(t, layout.endTurnBtn.centerX() - tw / 2f, layout.endTurnBtn.centerY() + ctx.dp(6), ctx.paint);
    }

    private String formatMmSs(long ms) {
        long totalSeconds = Math.max(0L, ms) / 1000L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        return minutes + ":" + (seconds < 10 ? "0" + seconds : String.valueOf(seconds));
    }

    private String shortLabel(String value, String fallback) {
        String src = value == null ? "" : value.trim();
        if (src.isEmpty()) src = fallback;
        if (src.length() <= 10) return src.toUpperCase();
        return src.substring(0, 10).toUpperCase();
    }
}
