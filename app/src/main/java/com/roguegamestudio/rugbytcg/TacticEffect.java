package com.roguegamestudio.rugbytcg;

import com.roguegamestudio.rugbytcg.core.GameState;

public interface TacticEffect {
    void onActivate(GameState ctx, boolean forYou);
    void onDeactivate(GameState ctx, boolean forYou);
}
