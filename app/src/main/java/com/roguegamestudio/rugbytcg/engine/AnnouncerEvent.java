package com.roguegamestudio.rugbytcg.engine;

import com.roguegamestudio.rugbytcg.CardId;

public final class AnnouncerEvent {
    public enum Type {
        MATCH_START,
        TURN_START,
        CARD_PLAYED,
        PHASE_RESULT,
        TRY_SCORED,
        MATCH_END
    }

    public enum Side {
        HOME,
        AWAY,
        NONE
    }

    public final Type type;
    public final Side side;
    public final CardId cardId;
    public final String cardName;
    public final int homeScore;
    public final int awayScore;
    public final int ballPos;
    public final long matchElapsedMs;
    public final boolean critical;
    public final long createdUptimeMs;

    public AnnouncerEvent(Type type,
                          Side side,
                          CardId cardId,
                          String cardName,
                          int homeScore,
                          int awayScore,
                          int ballPos,
                          long matchElapsedMs,
                          boolean critical,
                          long createdUptimeMs) {
        this.type = type != null ? type : Type.MATCH_START;
        this.side = side != null ? side : Side.NONE;
        this.cardId = cardId;
        this.cardName = cardName != null ? cardName.trim() : "";
        this.homeScore = homeScore;
        this.awayScore = awayScore;
        this.ballPos = ballPos;
        this.matchElapsedMs = Math.max(0L, matchElapsedMs);
        this.critical = critical;
        this.createdUptimeMs = Math.max(0L, createdUptimeMs);
    }

    public String signatureKey() {
        String id = cardId != null ? cardId.name() : "";
        return type.name() + "|" + side.name() + "|" + id + "|" + cardName + "|"
                + homeScore + "|" + awayScore + "|" + ballPos;
    }
}
