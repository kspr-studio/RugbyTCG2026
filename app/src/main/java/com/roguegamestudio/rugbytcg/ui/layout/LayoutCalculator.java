package com.roguegamestudio.rugbytcg.ui.layout;

import android.content.res.Resources;
import android.graphics.RectF;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.core.GameState;

import java.util.List;

public class LayoutCalculator {
    private final Resources resources;

    public LayoutCalculator(Resources resources) {
        this.resources = resources;
    }

    public void onSizeChanged(LayoutSpec spec, int w, int h) {
        float handTop = h * 0.78f;

        float side = w * 0.06f;
        float scoreH = dp(44);
        float scoreTop = dp(10);
        spec.scoreZone.set(side, scoreTop, w - side, scoreTop + scoreH);

        float turnH = dp(34);
        float turnTop = spec.scoreZone.bottom + dp(6);
        spec.turnZone.set(side, turnTop, w - side, turnTop + turnH);

        float statsH = dp(54);
        float statsTop = spec.turnZone.bottom + dp(6);
        spec.statsZone.set(side, statsTop, w - side, statsTop + statsH);

        float boardTop = spec.statsZone.bottom + dp(8);
        spec.boardZone.set(side, boardTop, w - side, handTop - (h * 0.03f));
        spec.handZone.set(0, handTop, w, h);

        spec.cardW = w * 0.24f;
        spec.cardH = spec.cardW * (h * 0.22f / (w * 0.28f));
        spec.handCardW = spec.cardW * 0.92f;
        spec.handCardH = spec.cardH * 0.92f;

        float btnW = w * 0.28f;
        float btnH = Math.min(h * 0.075f, spec.statsZone.height() - dp(8));
        float bx = spec.statsZone.right - btnW - dp(12);
        float by = spec.statsZone.centerY() - btnH / 2f;
        spec.endTurnBtn.set(bx, by, bx + btnW, by + btnH);

        float logW = dp(70);
        float logH = spec.turnZone.height() - dp(6);
        float logX = spec.turnZone.left + dp(8);
        float logY = spec.turnZone.centerY() - logH / 2f;
        spec.logBtn.set(logX, logY, logX + logW, logY + logH);

        float menuW = dp(80);
        float menuH = logH;
        float menuX = spec.logBtn.right + dp(8);
        float menuY = logY;
        spec.menuBtn.set(menuX, menuY, menuX + menuW, menuY + menuH);

        float tableW = dp(170);
        float tableX = spec.statsZone.left + dp(10);
        spec.statsTableArea.set(tableX, spec.statsZone.top + dp(6), tableX + tableW, spec.statsZone.bottom - dp(6));
        spec.statsHudArea.set(spec.statsTableArea.right + dp(10), spec.statsZone.top + dp(6),
                spec.endTurnBtn.left - dp(10), spec.statsZone.bottom - dp(6));

        spec.oppHandArea.set(
                spec.boardZone.right - dp(16) - (spec.cardW * 0.90f),
                spec.boardZone.top + dp(10),
                spec.boardZone.right - dp(16),
                spec.boardZone.top + dp(10) + dp(46)
        );
    }

    public void layoutAll(LayoutSpec spec, GameState state) {
        layoutYourHand(spec, state);
        layoutOppBoard(spec, state);
        layoutYourBoard(spec, state);
        buildOppHandBackRects(spec, state);
    }

    private void buildOppHandBackRects(LayoutSpec spec, GameState state) {
        spec.oppHandBackRects.clear();
        float backW = dp(18);
        float backH = dp(26);
        float gap = dp(6);
        int shown = Math.min(7, state.oppHand.size());
        float totalW = shown * backW + Math.max(0, shown - 1) * gap;
        float x = Math.max(spec.oppHandArea.left, spec.oppHandArea.right - totalW);
        float y = spec.oppHandArea.top + dp(24);
        for (int i = 0; i < shown; i++) {
            spec.oppHandBackRects.add(new RectF(x, y, x + backW, y + backH));
            x += backW + gap;
        }
    }

    private void layoutYourHand(LayoutSpec spec, GameState state) {
        spec.yourHandVisual.clear();
        float padding = dp(16);
        float gap = dp(14);

        int n = state.yourHand.size();
        float zoneW = spec.handZone.width() - padding * 2;
        float w = spec.handCardW;
        if (n > 0) {
            float maxW = (zoneW - gap * (n - 1)) / n;
            if (maxW <= 0f) {
                gap = 0f;
                maxW = zoneW / n;
            }
            if (maxW > 0f) {
                w = Math.min(w, maxW);
            }
        }
        float h = w * (spec.handCardH / spec.handCardW);
        float x = spec.handZone.left + padding;
        float y = spec.handZone.top + dp(40);
        for (Card c : state.yourHand) {
            RectF r = new RectF(x, y, x + w, y + h);
            spec.yourHandVisual.add(new VisualCard(c, r));
            x += w + gap;
        }
    }

    private void layoutOppBoard(LayoutSpec spec, GameState state) {
        float baseY = spec.boardZone.top + dp(18);
        float layoutHeight = layoutBoardStack(spec, state.oppBoard, spec.oppBoardVisual, baseY, false);
        float baseRowHeight = boardRowBaseHeight(spec);
        spec.oppBoardStackHeight = Math.max(layoutHeight, baseRowHeight);
    }

    private void layoutYourBoard(LayoutSpec spec, GameState state) {
        float rowGap = dp(16);
        float minBaseY = spec.boardZone.top + dp(18) + spec.oppBoardStackHeight + rowGap;
        float provisionalBaseY = minBaseY;
        float layoutHeight = layoutBoardStack(spec, state.yourBoard, spec.yourBoardVisual, provisionalBaseY, false);
        float bottomAnchoredY = spec.boardZone.bottom - layoutHeight - dp(16);
        float baseY = Math.max(minBaseY, bottomAnchoredY);
        if (baseY != provisionalBaseY) {
            layoutBoardStack(spec, state.yourBoard, spec.yourBoardVisual, baseY, false);
        }
    }

    private float boardRowBaseHeight(LayoutSpec spec) {
        float padding = dp(16);
        float gap = dp(14);
        float zoneW = spec.boardZone.width() - padding * 2;
        float desiredW = spec.cardW;
        float minW = dp(40);
        CardLayout layout = fitCardLayout(zoneW, 1, desiredW, minW, gap);
        return layout.w * (spec.cardH / spec.cardW);
    }

    private float layoutBoardStack(LayoutSpec spec, List<PlayerCard> board, List<VisualCard> out, float baseY, boolean stackUpward) {
        out.clear();

        int n = board.size();
        if (n == 0) return 0f;

        float padding = dp(16);
        float gap = dp(14);
        int maxCols = 3;
        int cols = Math.min(n, maxCols);
        float zoneW = spec.boardZone.width() - padding * 2;

        float desiredW = spec.cardW;
        float minW = dp(40);
        CardLayout layout = fitCardLayout(zoneW, cols, desiredW, minW, gap);

        float w = layout.w;
        float h = w * (spec.cardH / spec.cardW);
        float gapUsed = layout.gap;
        float layerOffsetY = dp(24);

        float x0 = spec.boardZone.left + padding;
        for (int i = 0; i < n; i++) {
            int col = i % maxCols;
            int layer = i / maxCols;
            float x = x0 + col * (w + gapUsed);
            float y = baseY + (stackUpward ? -layer * layerOffsetY : layer * layerOffsetY);
            RectF r = new RectF(x, y, x + w, y + h);
            out.add(new VisualCard(board.get(i), r));
        }

        int layers = (n + maxCols - 1) / maxCols;
        return h + (layers - 1) * layerOffsetY;
    }

    private CardLayout fitCardLayout(float zoneWidth, int count, float desiredW, float minW, float gap) {
        if (count <= 0 || zoneWidth <= 0) {
            return new CardLayout(desiredW, gap);
        }

        float total = count * desiredW + (count - 1) * gap;
        if (total <= zoneWidth) {
            return new CardLayout(Math.max(minW, desiredW), gap);
        }

        float scale = zoneWidth / total;
        float w = desiredW * scale;
        float g = gap * scale;

        if (w < minW) {
            float totalMin = count * minW + (count - 1) * g;
            if (totalMin <= zoneWidth) {
                w = minW;
            }
        }

        return new CardLayout(w, g);
    }

    private float dp(float v) {
        return v * resources.getDisplayMetrics().density;
    }

    private static class CardLayout {
        final float w;
        final float gap;

        CardLayout(float w, float gap) {
            this.w = w;
            this.gap = gap;
        }
    }
}
