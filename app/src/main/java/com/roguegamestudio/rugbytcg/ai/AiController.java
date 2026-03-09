package com.roguegamestudio.rugbytcg.ai;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.CardId;
import com.roguegamestudio.rugbytcg.PlayCard;
import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.TacticCard;
import com.roguegamestudio.rugbytcg.core.GameState;
import com.roguegamestudio.rugbytcg.engine.RulesEngine;

import java.util.ArrayList;
import java.util.List;

public class AiController {
    public interface Delegate {
        boolean playCard(Card card);
        int computeSideTotal(boolean you);
        void layoutAndInvalidate();
        long getPlayFlashDelayMs();
        void postDelayed(Runnable r, long delayMs);
        void onAiTurnComplete();
    }

    private static final int AI_MAX_PLAYS = 4;

    private final GameState state;
    private final RulesEngine rules;
    private int aiPlaysThisTurn = 0;
    private boolean aiUtilityPhase = true;

    public AiController(GameState state, RulesEngine rules) {
        this.state = state;
        this.rules = rules;
    }

    public void playTurn(Delegate delegate) {
        aiPlaysThisTurn = 0;
        aiUtilityPhase = true;
        runStep(delegate);
    }

    private void runStep(Delegate delegate) {
        if (aiPlaysThisTurn >= AI_MAX_PLAYS) {
            delegate.onAiTurnComplete();
            return;
        }

        Card toPlay = null;

        if (aiUtilityPhase) {
            toPlay = pickAiUtilityCard();
            if (toPlay == null) {
                aiUtilityPhase = false;
            }
        }

        if (!aiUtilityPhase) {
            int yourTotal = delegate.computeSideTotal(true);
            int oppTotal = delegate.computeSideTotal(false);

            if (oppTotal > yourTotal) {
                delegate.onAiTurnComplete();
                return;
            }

            List<PlayerCard> affordable = new ArrayList<>();
            for (Card c : state.oppHand) {
                if (c instanceof PlayerCard) {
                    PlayerCard pc = (PlayerCard) c;
                    if (pc.cost() <= state.oppMomentum) affordable.add(pc);
                }
            }
            if (affordable.isEmpty()) {
                delegate.onAiTurnComplete();
                return;
            }

            PlayerCard best = null;
            int bestNewTotal = oppTotal;
            int bestSta = -999;

            for (PlayerCard cand : affordable) {
                state.oppBoard.add(cand);
                int newTotal = delegate.computeSideTotal(false);
                state.oppBoard.remove(state.oppBoard.size() - 1);

                int staTie = cand.staCurrent;

                if (newTotal > bestNewTotal || (newTotal == bestNewTotal && staTie > bestSta)) {
                    bestNewTotal = newTotal;
                    bestSta = staTie;
                    best = cand;
                }
            }

            toPlay = best;
        }

        if (toPlay == null) {
            if (aiUtilityPhase) {
                aiUtilityPhase = false;
                runStep(delegate);
                return;
            }
            delegate.onAiTurnComplete();
            return;
        }

        if (!delegate.playCard(toPlay)) {
            delegate.onAiTurnComplete();
            return;
        }

        aiPlaysThisTurn++;
        delegate.layoutAndInvalidate();

        long delay = delegate.getPlayFlashDelayMs();
        delegate.postDelayed(() -> runStep(delegate), delay + 50);
    }

    private Card pickAiUtilityCard() {
        int bestScore = -1;
        Card best = null;
        for (Card c : state.oppHand) {
            if (c instanceof PlayerCard) continue;
            if ((c instanceof PlayCard || c instanceof TacticCard) && state.oppBoard.isEmpty()) continue;
            if (rules.cardCost(c) > state.oppMomentum) continue;
            if (c.id == CardId.DRIVE && state.driveUsedThisTurnOpp) continue;
            if (c.id == CardId.QUICK_PASS && state.oppBoard.size() < 2) continue;
            if (c.id == CardId.TIGHT_PLAY && state.oppBoard.size() < 3) continue;
            int score = aiUtilityScore(c);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private int aiUtilityScore(Card card) {
        switch (card.id) {
            case COUNTER_RUCK:
                return state.youWonLastPhase ? 4 : -1;
            case QUICK_PASS:
                return 3;
            case DRIVE:
                return (state.ballPos > -3) ? 2 : -1;
            case TIGHT_PLAY:
                return state.tightPlayOpp ? -1 : 1;
            default:
                return -1;
        }
    }
}
