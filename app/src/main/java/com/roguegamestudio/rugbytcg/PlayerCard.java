package com.roguegamestudio.rugbytcg;

public class PlayerCard extends Card {
    public final int pwr;
    public final int skl;
    public final int staMax;

    // Runtime state (changes during match)
    public int staCurrent;

    public PlayerAbility ability;

    public PlayerCard(CardId id, String name, String description, int pwr, int skl, int staMax, PlayerAbility ability) {
        super(id, name, description, CardType.PLAYER);
        this.pwr = pwr;
        this.skl = skl;
        this.staMax = staMax;
        this.staCurrent = staMax;
        this.ability = ability;
    }

    /** Simple cost rule: cost = PWR + SKL */
    public int cost() {
        return pwr + skl;
    }
}
