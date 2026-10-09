package com.novelagent.prompt.application;

/** Editorial roles share the reviewed templates used by settings and model requests. */
public final class DraftEditorialPrompts {
    private DraftEditorialPrompts() { }
    public static final String WRITE = AgentPromptDefaults.system("MANUSCRIPT");
    public static final String CHECK = AgentPromptDefaults.system("QUALITY_REVIEW");
    public static final String JUDGE = AgentPromptDefaults.system("DRAFT_JUDGE_REVISION");
}
