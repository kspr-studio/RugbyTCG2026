package com.roguegamestudio.rugbytcg.utils;

public interface UiCallbacks {
    void invalidate();
    void postInvalidateOnAnimation();
    void post(Runnable r);
    void postDelayed(Runnable r, long delayMs);
    void removeCallbacks(Runnable r);
    boolean isAttachedToWindow();
}
