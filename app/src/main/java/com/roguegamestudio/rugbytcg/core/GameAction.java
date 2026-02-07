package com.roguegamestudio.rugbytcg.core;

import com.roguegamestudio.rugbytcg.CardId;

public class GameAction {

    public enum Type {
        PLAY_CARD,
        END_TURN,
        INSPECT_CARD,
        CLOSE_OVERLAY
    }

    public final Type type;
    public final CardId cardId;
    public final boolean forYou;
    public final String source;

    private GameAction(Type type, CardId cardId, boolean forYou, String source) {
        this.type = type;
        this.cardId = cardId;
        this.forYou = forYou;
        this.source = source;
    }

    public static GameAction play(CardId cardId, boolean forYou) {
        return new GameAction(Type.PLAY_CARD, cardId, forYou, null);
    }

    public static GameAction endTurn(boolean forYou) {
        return new GameAction(Type.END_TURN, null, forYou, null);
    }

    public static GameAction inspect(CardId cardId, String source) {
        return new GameAction(Type.INSPECT_CARD, cardId, true, source);
    }

    public static GameAction closeOverlay(String source) {
        return new GameAction(Type.CLOSE_OVERLAY, null, true, source);
    }
}
