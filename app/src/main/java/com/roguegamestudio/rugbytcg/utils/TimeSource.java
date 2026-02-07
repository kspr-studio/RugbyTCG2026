package com.roguegamestudio.rugbytcg.utils;

public interface TimeSource {
    long nowUptimeMs();
    long nowElapsedMs();
}
