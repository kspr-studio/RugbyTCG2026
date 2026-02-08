package com.roguegamestudio.rugbytcg;

import com.roguegamestudio.rugbytcg.multiplayer.SupabaseService;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuActivityVersionGateTest {
    @Test
    public void determineVersionGateState_upToDate_enablesBootstrap() {
        SupabaseService.VersionStatus status = new SupabaseService.VersionStatus(
                true,
                "",
                BuildConfig.VERSION_CODE,
                BuildConfig.VERSION_CODE,
                true
        );
        int state = MenuActivity.determineVersionGateState(status);
        assertEquals(MenuActivity.VERSION_STATE_UP_TO_DATE, state);
        assertTrue(MenuActivity.shouldAllowOnlineBootstrap(state));
    }

    @Test
    public void determineVersionGateState_outdated_blocksBootstrap() {
        SupabaseService.VersionStatus status = new SupabaseService.VersionStatus(
                false,
                "client_upgrade_required",
                BuildConfig.VERSION_CODE + 1,
                BuildConfig.VERSION_CODE,
                false
        );
        int state = MenuActivity.determineVersionGateState(status);
        assertEquals(MenuActivity.VERSION_STATE_OUTDATED, state);
        assertFalse(MenuActivity.shouldAllowOnlineBootstrap(state));
    }

    @Test
    public void determineVersionGateState_unknownStatus_staysUnknown() {
        SupabaseService.VersionStatus status = new SupabaseService.VersionStatus(
                false,
                "",
                0,
                0,
                false
        );
        int state = MenuActivity.determineVersionGateState(status);
        assertEquals(MenuActivity.VERSION_STATE_UNKNOWN, state);
        assertFalse(MenuActivity.shouldAllowOnlineBootstrap(state));
    }
}
