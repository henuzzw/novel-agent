package com.novelagent.planning.application;

public class PlanningCheckpointException extends RuntimeException {
    private final boolean missing;

    public PlanningCheckpointException(String message, boolean missing) {
        super(message);
        this.missing = missing;
    }

    public boolean isMissing() { return missing; }
}
