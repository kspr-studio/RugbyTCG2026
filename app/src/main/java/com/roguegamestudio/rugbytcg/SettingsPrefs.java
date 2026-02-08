package com.roguegamestudio.rugbytcg;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsPrefs {
    public static final String PREFS = "rugby_prefs";
    public static final String KEY_CROWD_VOLUME_PERCENT = "crowd_volume_percent";
    public static final int DEFAULT_CROWD_VOLUME_PERCENT = 80;

    private SettingsPrefs() {
    }

    public static int getCrowdVolumePercent(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        int stored = prefs.getInt(KEY_CROWD_VOLUME_PERCENT, DEFAULT_CROWD_VOLUME_PERCENT);
        return clampPercent(stored);
    }

    public static float getCrowdVolumeLevel(Context context) {
        return getCrowdVolumePercent(context) / 100f;
    }

    public static void setCrowdVolumePercent(Context context, int value) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_CROWD_VOLUME_PERCENT, clampPercent(value)).apply();
    }

    public static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }
}
