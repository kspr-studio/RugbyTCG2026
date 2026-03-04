package com.roguegamestudio.rugbytcg.core;

import com.roguegamestudio.rugbytcg.Card;
import com.roguegamestudio.rugbytcg.PlayerCard;
import com.roguegamestudio.rugbytcg.TacticCard;

import java.util.ArrayList;
import java.util.List;

public class GameState {

    // Match state (HOME = local side, AWAY = opponent side in current device view)
    public int homeScore = 0;
    public int awayScore = 0;
    public long matchStartElapsedMs = 0L;
    public long matchDurationMs = 3L * 60L * 1000L;
    public boolean matchOver = false;
    public boolean suddenDeath = false;

    // Ball position: -3 (danger for you) ... 0 ... +3 (danger for opponent)
    public int ballPos = 0;

    // Last phase outcome from YOUR perspective
    public boolean youWonLastPhase = false;
    public boolean youLostLastPhase = false;

    // Momentum (mana) for current turn
    public int yourMomentum = 5;
    public int oppMomentum = 5;

    // Hands + boards
    public final List<Card> yourHand = new ArrayList<>();
    public final List<PlayerCard> yourBoard = new ArrayList<>();
    public final List<Card> oppHand = new ArrayList<>();
    public final List<PlayerCard> oppBoard = new ArrayList<>();

    // Per-phase temp bonuses
    public int tempPwrBonusYou = 0;
    public int tempSklBonusYou = 0;
    public int tempPwrBonusOpp = 0;
    public int tempSklBonusOpp = 0;

    // Used by some abilities like Opportunist
    public int yourCardsPlayedThisPhase = 0;
    public int oppCardsPlayedThisPhase = 0;

    // Momentum bonuses granted for next turn (e.g., Playmaker)
    public int nextTurnMomentumBonusYou = 0;
    public int nextTurnMomentumBonusOpp = 0;

    // Active tactic flags (phase-only, cleared on resolve)
    public boolean tightPlayYou = false;
    public boolean tightPlayOpp = false;

    public TacticCard activeTacticYou = null;
    public TacticCard activeTacticOpp = null;

    // Per-turn limits
    public boolean driveUsedThisTurnYou = false;
    public boolean driveUsedThisTurnOpp = false;

    // Simple banner text for  YOU WIN / YOU LOSE
    public String bannerText = "";
    public long bannerUntilMs = 0;

    public void hideBanner() {
        bannerText = "";
        bannerUntilMs = 0;
    }

    public void showBanner(String text, long now, long durationMs) {
        bannerText = text;
        bannerUntilMs = now + durationMs;
    }

    public void resetPhaseTemps() {
        tempPwrBonusYou = tempSklBonusYou = 0;
        tempPwrBonusOpp = tempSklBonusOpp = 0;
        yourCardsPlayedThisPhase = 0;
        oppCardsPlayedThisPhase = 0;
        tightPlayYou = false;
        tightPlayOpp = false;
    }
}
