package com.roguegamestudio.rugbytcg;

import com.roguegamestudio.rugbytcg.core.GameState;

public interface PlayEffect {
    void apply(GameState ctx, boolean forYou);
}
