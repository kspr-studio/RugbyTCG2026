package com.roguegamestudio.rugbytcg;

import com.roguegamestudio.rugbytcg.core.GameState;

import java.util.ArrayList;
import java.util.List;

public final class StarterDeck {

    private StarterDeck() {}

    public static List<Card> build() {
        List<Card> deck = new ArrayList<>();

        // 1) Flanker: +1 PWR while contesting
        deck.add(new PlayerCard(
                CardId.FLANKER, "Flanker", "+1 PWR while contesting.",
                3, 1, 4,
                (ctx, self) -> {
                    PhaseBonus b = new PhaseBonus();
                    b.bonusPwr += 1;
                    return b;
                }
        ));

        // 2) Prop: vanilla muscle
        deck.add(new PlayerCard(
                CardId.PROP, "Prop", "Straight-up power.",
                4, 0, 3,
                (ctx, self) -> PhaseBonus.none()
        ));

        // 3) Playmaker: when played, gain +1 Momentum next turn
        // We'll implement as: if this card is on your board, it grants +1 SKL bonus? No.
        // Better: give a phase bonus placeholder and later implement "on played" hook.
        // For now: grant +1 momentum NEXT turn via ctx flag (we'll add later).
        // Minimal now: it gives no phase bonus; we store the idea in name/ID.
        deck.add(new PlayerCard(
                CardId.PLAYMAKER, "Playmaker", "Gain +1 Momentum next turn.",
                1, 3, 3,
                (ctx, self) -> PhaseBonus.none()
        ));

        // 4) Breaker: if you win this phase, gain +1 Ball Position
        // We'll represent as phase bonus of +1 ball pos ONLY IF you win.
        deck.add(new PlayerCard(
                CardId.BREAKER, "Breaker", "If you win the phase, push the ball +1 extra.",
                2, 3, 2,
                (ctx, self) -> {
                    // applied after resolution in code later; placeholder bonus object
                    return PhaseBonus.none();
                }
        ));

        // 5) Anchor: loses 1 less STA when losing a phase
        // This affects stamina drain, not totals; implement later in drain logic.
        deck.add(new PlayerCard(
                CardId.ANCHOR, "Anchor", "Lose 1 less STA when you lose a phase.",
                2, 0, 5,
                (ctx, self) -> PhaseBonus.none()
        ));

        // 6) Opportunist: if opponent plays more cards this phase, gain +2 PWR
        deck.add(new PlayerCard(
                CardId.OPPORTUNIST, "Chancer", "If opponent plays more cards this phase, +2 PWR.",
                1, 2, 2,
                (ctx, self) -> {
                    PhaseBonus b = new PhaseBonus();
                    if (ctx.oppCardsPlayedThisPhase > ctx.yourCardsPlayedThisPhase) {
                        b.bonusPwr += 2;
                    }
                    return b;
                }
        ));

        // 7) Counter Ruck: if you lost last phase, +3 PWR this phase
        deck.add(new PlayCard(
            CardId.COUNTER_RUCK, "Counter Ruck", "If you lost last phase, gain +3 PWR this phase.",
            2, // cost (tuneable)
            (ctx, forYou) -> {
                boolean lostLastPhase = forYou ? ctx.youLostLastPhase : ctx.youWonLastPhase;
                if (lostLastPhase) {
                    if (forYou) ctx.tempPwrBonusYou += 3;
                    else ctx.tempPwrBonusOpp += 3;
                }
            }
        ));

        // 8) Quick Pass: +2 SKL this phase
        deck.add(new PlayCard(
                CardId.QUICK_PASS, "Quick Pass", "+2 SKL this phase.",
                2,
                (ctx, forYou) -> {
                    if (forYou) ctx.tempSklBonusYou += 2;
                    else ctx.tempSklBonusOpp += 2;
                }
        ));

        // 9) Drive: +1 Ball Position immediately then resolve phase
        deck.add(new PlayCard(
                CardId.DRIVE, "Drive", "Push the ball +1 immediately.",
                3,
                (ctx, forYou) -> {
                    int delta = forYou ? 1 : -1;
                    int next = ctx.ballPos + delta;
                    if (next > 4) next = 4;
                    if (next < -4) next = -4;
                    ctx.ballPos = next;
                }
        ));

        // 10) Tight Play (Tactic): +1 PWR to all players, but STA drains faster
        deck.add(new TacticCard(
                CardId.TIGHT_PLAY, "Tight Play", "+1 PWR to your players this phase, but they lose +1 extra STA.",
                4,
                new TacticEffect() {
                    @Override public void onActivate(GameState ctx, boolean forYou) {
                        if (forYou) ctx.tightPlayYou = true;
                        else ctx.tightPlayOpp = true;
                    }
                    @Override public void onDeactivate(GameState ctx, boolean forYou) {
                        if (forYou) ctx.tightPlayYou = false;
                        else ctx.tightPlayOpp = false;
                    }
                }
        ));

        return deck;
    }
}
