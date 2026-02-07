package com.roguegamestudio.rugbytcg.ui.render;

import android.graphics.Canvas;

import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.engine.MatchEngine;
import com.roguegamestudio.rugbytcg.engine.RulesEngine;
import com.roguegamestudio.rugbytcg.engine.TurnEngine;
import com.roguegamestudio.rugbytcg.tutorial.TutorialController;
import com.roguegamestudio.rugbytcg.ui.UiState;
import com.roguegamestudio.rugbytcg.ui.input.DragState;
import com.roguegamestudio.rugbytcg.ui.layout.LayoutSpec;

public class GameRenderer {
    private final RenderContext ctx;
    private final CardRenderer cardRenderer;
    private final HudRenderer hudRenderer;
    private final BoardRenderer boardRenderer;
    private final HandRenderer handRenderer;
    private final OverlayRenderer overlayRenderer;
    private final LogRenderer logRenderer;
    private final TutorialRenderer tutorialRenderer;

    public GameRenderer(RenderContext ctx) {
        this.ctx = ctx;
        this.cardRenderer = new CardRenderer();
        this.hudRenderer = new HudRenderer();
        this.boardRenderer = new BoardRenderer(cardRenderer);
        this.handRenderer = new HandRenderer(cardRenderer);
        this.overlayRenderer = new OverlayRenderer(cardRenderer);
        this.logRenderer = new LogRenderer(cardRenderer);
        this.tutorialRenderer = new TutorialRenderer();
    }

    public void draw(Canvas canvas,
                     LayoutSpec layout,
                     GameState state,
                     UiState ui,
                     DragState dragState,
                     RulesEngine rules,
                     MatchEngine matchEngine,
                     TurnEngine turnEngine,
                     TutorialController tutorial) {
        long now = android.os.SystemClock.uptimeMillis();
        ctx.frameElapsedMs = android.os.SystemClock.elapsedRealtime();
        if (ctx.lastFrameMs == 0L) ctx.lastFrameMs = now;
        ctx.frameDtSeconds = Math.min(0.05f, Math.max(0f, (now - ctx.lastFrameMs) / 1000f));
        ctx.lastFrameMs = now;

        cardRenderer.pruneStaminaCache(ctx, state);

        hudRenderer.drawScoreBar(canvas, ctx, layout, state, ui, matchEngine, tutorial);
        hudRenderer.drawTurnBar(canvas, ctx, layout, turnEngine, tutorial);
        hudRenderer.drawStatsBar(canvas, ctx, layout, state, ui, rules);

        boardRenderer.drawBoard(canvas, ctx, layout, state, ui, now);
        handRenderer.drawHand(canvas, ctx, layout, state, dragState, tutorial, now);

        hudRenderer.drawEndTurnButton(canvas, ctx, layout, state, ui, turnEngine, tutorial);

        if (now < state.bannerUntilMs && state.bannerText != null && !state.bannerText.isEmpty()) {
            overlayRenderer.drawBanner(canvas, ctx, layout, state);
        }

        overlayRenderer.drawFlashOverlay(canvas, ctx, layout, ui, now);

        if (!ui.showLog && !ui.showInspect && now < ui.burnFlashUntilMs && ui.burnFlashCard != null) {
            overlayRenderer.drawBurnOverlay(canvas, ctx, layout, ui);
        } else if (ui.burnFlashCard != null && now >= ui.burnFlashUntilMs) {
            ui.burnFlashCard = null;
        }

        if (!ui.showLog && !ui.showInspect && now < ui.playFlashUntilMs && ui.playFlashCard != null) {
            overlayRenderer.drawPlayedCardOverlay(canvas, ctx, layout, ui, now);
        }

        if (ui.showLog) {
            logRenderer.drawLogOverlay(canvas, ctx, ui, layout.handZone.right, layout.handZone.bottom, layout.cardW, layout.cardH);
        }
        if (ui.showInspect && ui.inspectCard != null) {
            overlayRenderer.drawInspectOverlay(canvas, ctx, layout, ui);
        }
        if (tutorial != null && tutorial.isActive() && !ui.showLog && !ui.showInspect
                && (ui.playFlashCard == null || now >= ui.playFlashUntilMs)
                && (ui.burnFlashCard == null || now >= ui.burnFlashUntilMs)) {
            tutorialRenderer.drawTutorialOverlay(canvas, ctx, layout, tutorial, dragState);
        }
    }
}
