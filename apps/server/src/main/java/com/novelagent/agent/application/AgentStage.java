package com.novelagent.agent.application;

public enum AgentStage {
    STORY_DIRECTION(false),
    STORY_BIBLE(false),
    OUTLINE(false),
    CHAPTER_CONTRACT(true),
    MANUSCRIPT(true),
    CHAPTER_REVIEW(true);

    private final boolean usesLongTermMemory;

    AgentStage(boolean usesLongTermMemory) {
        this.usesLongTermMemory = usesLongTermMemory;
    }

    public boolean usesLongTermMemory() {
        return usesLongTermMemory;
    }
}
