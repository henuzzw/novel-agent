package com.novelagent.planning.application;

public final class CreationPreparationPrompt {
    private CreationPreparationPrompt() { }
    public static String world() {
        return com.novelagent.prompt.application.AgentPromptDefaults.system("CREATION_PREPARATION_WORLD");
    }
    public static String plot() {
        return com.novelagent.prompt.application.AgentPromptDefaults.system("CREATION_PREPARATION_PLOT");
    }
    public static String review() {
        return com.novelagent.prompt.application.AgentPromptDefaults.system("CREATION_PREPARATION_REVIEW");
    }
}
