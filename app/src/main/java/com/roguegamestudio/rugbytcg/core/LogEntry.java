package com.roguegamestudio.rugbytcg.core;

public final class LogEntry {
    public final String time;
    public final String label;
    public final CardSnapshot snapshot;
    public final boolean opponent;

    public LogEntry(String time, String label, CardSnapshot snapshot, boolean opponent) {
        this.time = time;
        this.label = label;
        this.snapshot = snapshot;
        this.opponent = opponent;
    }
}
