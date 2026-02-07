package com.roguegamestudio.rugbytcg.ui.log;

import com.roguegamestudio.rugbytcg.ui.UiState;

public class LogLayoutHelper {
    public static void ensureLayout(UiState ui, float w, float h, float dp) {
        float panelW = w * 0.82f;
        float panelH = h * 0.60f;
        float left = (w - panelW) / 2f;
        float top = (h - panelH) / 2f;
        ui.logPanel.set(left, top, left + panelW, top + panelH);

        float pad = dp * 14f;
        ui.logListArea.set(
                ui.logPanel.left + pad,
                ui.logPanel.top + dp * 38f,
                ui.logPanel.right - pad,
                ui.logPanel.bottom - pad
        );
    }

    public static float getScrollMax(UiState ui, float cardH, float dp) {
        float lineGap = dp * 10f;
        float thumbH = cardH * 0.5f;
        float contentH = ui.playLog.size() * (thumbH + lineGap);
        return Math.max(0f, contentH - ui.logListArea.height());
    }

    public static float getRowHeight(float cardH, float dp) {
        float lineGap = dp * 10f;
        float thumbH = cardH * 0.5f;
        return thumbH + lineGap;
    }

    public static int getIndexAt(UiState ui, float y, float cardH, float dp) {
        float rowH = getRowHeight(cardH, dp);
        float offset = y - ui.logListArea.top + ui.logScrollY;
        int index = (int) (offset / rowH);
        return (index >= 0 && index < ui.playLog.size()) ? index : -1;
    }
}
