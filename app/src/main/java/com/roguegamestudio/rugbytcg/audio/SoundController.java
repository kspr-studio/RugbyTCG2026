package com.roguegamestudio.rugbytcg.audio;

import android.media.AudioManager;
import android.media.ToneGenerator;

public class SoundController {
    private ToneGenerator toneGen;

    public void init() {
        try {
            toneGen = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
        } catch (RuntimeException e) {
            toneGen = null;
        }
    }

    public void release() {
        if (toneGen != null) {
            toneGen.release();
            toneGen = null;
        }
    }

    public void playTone(int tone, int durationMs) {
        if (toneGen == null) return;
        toneGen.startTone(tone, durationMs);
    }
}
