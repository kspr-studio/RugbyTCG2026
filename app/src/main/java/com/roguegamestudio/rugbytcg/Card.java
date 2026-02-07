package com.roguegamestudio.rugbytcg;

public abstract class Card {
    public final CardId id;
    public final String name;
    public final CardType type;
    public final String description;

    protected Card(CardId id, String name, String description, CardType type) {
        this.id = id;
        this.name = name;
        this.description = description == null ? "" : description;
        this.type = type;
    }
}
