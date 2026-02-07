package com.roguegamestudio.rugbytcg.core;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.PlayCard;
import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.TacticCard;

public final class CardSnapshot {
    public final Card card;

    public CardSnapshot(Card card) {
        this.card = card;
    }

    public static CardSnapshot from(Card card) {
        if (card == null) return new CardSnapshot(null);
        if (card instanceof PlayerCard) {
            PlayerCard src = (PlayerCard) card;
            PlayerCard pc = new PlayerCard(src.id, src.name, src.description, src.pwr, src.skl, src.staMax, src.ability);
            pc.staCurrent = src.staCurrent;
            return new CardSnapshot(pc);
        }
        if (card instanceof PlayCard) {
            PlayCard src = (PlayCard) card;
            return new CardSnapshot(new PlayCard(src.id, src.name, src.description, src.cost, src.effect));
        }
        if (card instanceof TacticCard) {
            TacticCard src = (TacticCard) card;
            return new CardSnapshot(new TacticCard(src.id, src.name, src.description, src.cost, src.effect));
        }
        return new CardSnapshot(card);
    }
}
