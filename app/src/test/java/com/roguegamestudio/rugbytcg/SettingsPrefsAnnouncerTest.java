package com.roguegamestudio.rugbytcg;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SettingsPrefsAnnouncerTest {
    @Test
    public void announcerDefaults_areAsExpected() {
        assertTrue(SettingsPrefs.DEFAULT_ANNOUNCER_ENABLED);
        assertEquals(85, SettingsPrefs.DEFAULT_ANNOUNCER_VOLUME_PERCENT);
    }
}
