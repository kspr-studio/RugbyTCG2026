package com.roguegamestudio.rugbytcg;

public class TacticCard extends Card {
    public final int cost;
    public final TacticEffect effect;

    public TacticCard(CardId id, String name, String description, int cost, TacticEffect effect) {
        super(id, name, description, CardType.TACTIC);
        this.cost = cost;
        this.effect = effect;
    }
}
