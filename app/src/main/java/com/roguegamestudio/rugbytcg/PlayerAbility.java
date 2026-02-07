package com.roguegamestudio.rugbytcg;

import com.roguegamestudio.rugbytcg.core.GameState;

public interface PlayerAbility {
    /** Called when calculating this card's contribution to the current phase. */
    PhaseBonus getPhaseBonus(GameState ctx, PlayerCard self);
}
