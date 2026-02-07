package com.roguegamestudio.rugbytcg;

public class PlayCard extends Card {
    public final int cost;
    public final PlayEffect effect;

    public PlayCard(CardId id, String name, String description, int cost, PlayEffect effect) {
        super(id, name, description, CardType.PLAY);
        this.cost = cost;
        this.effect = effect;
    }
}
