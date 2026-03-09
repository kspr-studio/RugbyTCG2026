package com.roguegamestudio.rugbytcg.multiplayer;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PhaseStateSubmissionPolicyTest {
    @Test
    public void shouldEagerlySubmitAuthoritativePhaseState_onlyForPlayCard() {
        assertTrue(PhaseStateSubmissionPolicy.shouldEagerlySubmitAuthoritativePhaseState("play_card"));
        assertFalse(PhaseStateSubmissionPolicy.shouldEagerlySubmitAuthoritativePhaseState("end_turn"));
        assertFalse(PhaseStateSubmissionPolicy.shouldEagerlySubmitAuthoritativePhaseState("kickoff_ready"));
        assertFalse(PhaseStateSubmissionPolicy.shouldEagerlySubmitAuthoritativePhaseState("unknown"));
        assertFalse(PhaseStateSubmissionPolicy.shouldEagerlySubmitAuthoritativePhaseState(null));
    }
}
