package com.roguegamestudio.rugbytcg.engine;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.CardId;
import com.roguegamestudio.rugbytcg.PhaseBonus;
import com.roguegamestudio.rugbytcg.PlayCard;
import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.TacticCard;
import com.roguegamestudio.rugbytcg.core.GameState;

import java.util.List;

public class RulesEngine {
    public enum PlayFailReason { NONE, NO_BOARD, NO_MOMENTUM, DRIVE_USED }

    public static class PlayResult {
        public final boolean success;
        public final PlayFailReason failReason;

        public PlayResult(boolean success, PlayFailReason failReason) {
            this.success = success;
            this.failReason = failReason;
        }
    }

    public enum PhaseOutcome { YOU_WIN, OPP_WIN, TIE }

    public static class PhaseResolution {
        public final int yourTotal;
        public final int oppTotal;
        public final PhaseOutcome outcome;
        public final boolean homeTry;
        public final boolean awayTry;

        public PhaseResolution(int yourTotal, int oppTotal, PhaseOutcome outcome, boolean homeTry, boolean awayTry) {
            this.yourTotal = yourTotal;
            this.oppTotal = oppTotal;
            this.outcome = outcome;
            this.homeTry = homeTry;
            this.awayTry = awayTry;
        }
    }

    private final int ballMin;
    private final int ballMax;

    public RulesEngine(int ballMin, int ballMax) {
        this.ballMin = ballMin;
        this.ballMax = ballMax;
    }

    public int cardCost(Card card) {
        if (card instanceof PlayerCard) return ((PlayerCard) card).cost();
        if (card instanceof PlayCard) return ((PlayCard) card).cost;
        if (card instanceof TacticCard) return ((TacticCard) card).cost;
        return 0;
    }

    public PlayResult tryPlayCard(GameState state, Card card, boolean forYou) {
        if (card == null) return new PlayResult(false, PlayFailReason.NONE);

        if ((card instanceof PlayCard || card instanceof TacticCard)) {
            boolean hasBoard = forYou ? !state.yourBoard.isEmpty() : !state.oppBoard.isEmpty();
            if (!hasBoard) {
                return new PlayResult(false, PlayFailReason.NO_BOARD);
            }
        }

        if (card.id == CardId.DRIVE) {
            boolean used = forYou ? state.driveUsedThisTurnYou : state.driveUsedThisTurnOpp;
            if (used) {
                return new PlayResult(false, PlayFailReason.DRIVE_USED);
            }
        }

        int cost = cardCost(card);
        if (forYou) {
            if (state.yourMomentum < cost) {
                return new PlayResult(false, PlayFailReason.NO_MOMENTUM);
            }
            state.yourMomentum -= cost;
            state.yourHand.remove(card);
        } else {
            if (state.oppMomentum < cost) {
                return new PlayResult(false, PlayFailReason.NO_MOMENTUM);
            }
            state.oppMomentum -= cost;
            state.oppHand.remove(card);
        }

        if (card.id == CardId.DRIVE) {
            if (forYou) state.driveUsedThisTurnYou = true;
            else state.driveUsedThisTurnOpp = true;
        }

        playCardForSide(state, card, forYou);
        return new PlayResult(true, PlayFailReason.NONE);
    }

    public void playCardForSide(GameState state, Card card, boolean forYou) {
        if (card instanceof PlayerCard) {
            playPlayerCard(state, (PlayerCard) card, forYou);
            return;
        }
        if (card instanceof PlayCard) {
            playPlayCard(state, (PlayCard) card, forYou);
            return;
        }
        if (card instanceof TacticCard) {
            playTacticCard(state, (TacticCard) card, forYou);
        }
    }

    public void playPlayerCard(GameState state, PlayerCard pc, boolean forYou) {
        if (forYou) {
            state.yourBoard.add(pc);
            state.yourCardsPlayedThisPhase += 1;
            if (pc.id == CardId.PLAYMAKER) state.nextTurnMomentumBonusYou += 1;
        } else {
            state.oppBoard.add(pc);
            state.oppCardsPlayedThisPhase += 1;
            if (pc.id == CardId.PLAYMAKER) state.nextTurnMomentumBonusOpp += 1;
        }
    }

    public void playPlayCard(GameState state, PlayCard pc, boolean forYou) {
        if (pc.effect != null) pc.effect.apply(state, forYou);
        if (forYou) state.yourCardsPlayedThisPhase += 1;
        else state.oppCardsPlayedThisPhase += 1;
    }

    public void playTacticCard(GameState state, TacticCard tc, boolean forYou) {
        deactivateTactic(state, forYou);
        if (tc.effect != null) tc.effect.onActivate(state, forYou);
        if (forYou) {
            state.activeTacticYou = tc;
            state.yourCardsPlayedThisPhase += 1;
        } else {
            state.activeTacticOpp = tc;
            state.oppCardsPlayedThisPhase += 1;
        }
    }

    public PhaseResolution resolvePhase(GameState state) {
        int yourTotal = computeSideTotal(state, true);
        int oppTotal = computeSideTotal(state, false);

        PhaseOutcome outcome;
        if (yourTotal > oppTotal) {
            state.youWonLastPhase = true;
            state.youLostLastPhase = false;
            state.ballPos = clamp(state.ballPos + 1, ballMin, ballMax);
            outcome = PhaseOutcome.YOU_WIN;
        } else if (oppTotal > yourTotal) {
            state.youWonLastPhase = false;
            state.youLostLastPhase = true;
            state.ballPos = clamp(state.ballPos - 1, ballMin, ballMax);
            outcome = PhaseOutcome.OPP_WIN;
        } else {
            state.youWonLastPhase = false;
            state.youLostLastPhase = false;
            outcome = PhaseOutcome.TIE;
        }

        if (outcome == PhaseOutcome.YOU_WIN && hasBreaker(state.yourBoard)) {
            state.ballPos = clamp(state.ballPos + 1, ballMin, ballMax);
        } else if (outcome == PhaseOutcome.OPP_WIN && hasBreaker(state.oppBoard)) {
            state.ballPos = clamp(state.ballPos - 1, ballMin, ballMax);
        }

        boolean youLost = oppTotal > yourTotal;
        boolean oppLost = yourTotal > oppTotal;

        drainSta(state.yourBoard, youLost, state.tightPlayYou);
        drainSta(state.oppBoard, oppLost, state.tightPlayOpp);

        removeExhausted(state.yourBoard);
        removeExhausted(state.oppBoard);

        deactivateTactic(state, true);
        deactivateTactic(state, false);

        boolean homeTry = state.ballPos >= ballMax;
        boolean awayTry = state.ballPos <= ballMin;

        return new PhaseResolution(yourTotal, oppTotal, outcome, homeTry, awayTry);
    }

    public int computeSideTotal(GameState state, boolean you) {
        int total = 0;
        List<PlayerCard> board = you ? state.yourBoard : state.oppBoard;
        int tempPwr = you ? state.tempPwrBonusYou : state.tempPwrBonusOpp;
        int tempSkl = you ? state.tempSklBonusYou : state.tempSklBonusOpp;
        boolean tightPlay = you ? state.tightPlayYou : state.tightPlayOpp;

        for (PlayerCard pc : board) {
            int pwr = pc.pwr + (tightPlay ? 1 : 0);
            int skl = pc.skl;
            if (pc.ability != null) {
                PhaseBonus b = pc.ability.getPhaseBonus(state, pc);
                if (b != null) {
                    pwr += b.bonusPwr;
                    skl += b.bonusSkl;
                }
            }
            total += (pwr + skl);
        }

        total += (tempPwr + tempSkl);
        return total;
    }

    private void drainSta(List<PlayerCard> board, boolean lostPhase, boolean tightPlay) {
        int baseDrain = 1 + (tightPlay ? 1 : 0);
        for (PlayerCard pc : board) {
            int drain = baseDrain;
            if (lostPhase && pc.id == CardId.ANCHOR) {
                drain = Math.max(0, drain - 1);
            }
            pc.staCurrent -= drain;
        }
    }

    private void removeExhausted(List<PlayerCard> board) {
        for (int i = board.size() - 1; i >= 0; i--) {
            if (board.get(i).staCurrent <= 0) board.remove(i);
        }
    }

    private boolean hasBreaker(List<PlayerCard> board) {
        for (PlayerCard pc : board) {
            if (pc.id == CardId.BREAKER) return true;
        }
        return false;
    }

    public void deactivateTactic(GameState state, boolean forYou) {
        TacticCard active = forYou ? state.activeTacticYou : state.activeTacticOpp;
        if (active != null && active.effect != null) {
            active.effect.onDeactivate(state, forYou);
        }
        if (forYou) {
            state.activeTacticYou = null;
            state.tightPlayYou = false;
        } else {
            state.activeTacticOpp = null;
            state.tightPlayOpp = false;
        }
    }

    private int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
