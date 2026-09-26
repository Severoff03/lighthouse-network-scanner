package ru.lighthouse.core;

public enum ScanProfile {
    QUICK(1, 90), DEEP(2, 300);

    public final int passes;
    public final int budgetSeconds;
    ScanProfile(int passes, int budgetSeconds) { this.passes = passes; this.budgetSeconds = budgetSeconds; }
}
