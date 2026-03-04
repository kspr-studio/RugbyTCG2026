package com.roguegamestudio.rugbytcg;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class SettingsPrefsAnnouncerTest {
    @Test
    public void announcerPrefs_roundTripAndClamp() {
        Context context = ApplicationProvider.getApplicationContext();
        boolean originalEnabled = SettingsPrefs.getAnnouncerEnabled(context);
        int originalVolume = SettingsPrefs.getAnnouncerVolumePercent(context);

        try {
            SettingsPrefs.setAnnouncerEnabled(context, false);
            assertFalse(SettingsPrefs.getAnnouncerEnabled(context));
            SettingsPrefs.setAnnouncerEnabled(context, true);
            assertTrue(SettingsPrefs.getAnnouncerEnabled(context));

            SettingsPrefs.setAnnouncerVolumePercent(context, -25);
            assertEquals(0, SettingsPrefs.getAnnouncerVolumePercent(context));
            SettingsPrefs.setAnnouncerVolumePercent(context, 140);
            assertEquals(100, SettingsPrefs.getAnnouncerVolumePercent(context));
            SettingsPrefs.setAnnouncerVolumePercent(context, 37);
            assertEquals(37, SettingsPrefs.getAnnouncerVolumePercent(context));
        } finally {
            SettingsPrefs.setAnnouncerEnabled(context, originalEnabled);
            SettingsPrefs.setAnnouncerVolumePercent(context, originalVolume);
        }
    }
}
