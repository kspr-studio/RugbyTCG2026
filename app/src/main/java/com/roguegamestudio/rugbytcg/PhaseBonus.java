package com.roguegamestudio.rugbytcg;

public class PhaseBonus {
    public int bonusPwr = 0;
    public int bonusSkl = 0;
    public int bonusBallPosition = 0;

    public static PhaseBonus none() {
        return new PhaseBonus();
    }
}
