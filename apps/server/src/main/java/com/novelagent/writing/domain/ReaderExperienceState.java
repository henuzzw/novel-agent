package com.novelagent.writing.domain;

public enum ReaderExperienceState {
    PLANNED, SET_UP, REINFORCED, PAYOFF, ABANDONED, OPEN;

    public void requireTransition(ReaderExperienceState next, boolean previousStale) {
        if (next == null || next == PLANNED) throw new IllegalArgumentException("实际进展不能设为计划状态");
        if (this == next && (previousStale || this == REINFORCED)) return;
        boolean allowed = switch (this) {
            case PLANNED -> next == SET_UP || next == OPEN || next == ABANDONED;
            case SET_UP, REINFORCED, OPEN -> next != SET_UP || this == OPEN;
            case PAYOFF, ABANDONED -> false;
        };
        if (!allowed) throw new IllegalArgumentException("无效状态迁移：" + this + " -> " + next);
    }
}
