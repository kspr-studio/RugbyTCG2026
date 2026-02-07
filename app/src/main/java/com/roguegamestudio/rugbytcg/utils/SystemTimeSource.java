package com.roguegamestudio.rugbytcg.utils;

import android.os.SystemClock;

public class SystemTimeSource implements TimeSource {
    @Override
    public long nowUptimeMs() {
        return SystemClock.uptimeMillis();
    }

    @Override
    public long nowElapsedMs() {
        return SystemClock.elapsedRealtime();
    }
}
