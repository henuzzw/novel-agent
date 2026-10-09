package com.novelagent.planning.infrastructure;

import com.novelagent.prompt.application.AgentPromptCatalog;

/** Only upstream planning shares history; independent B/C reviewers keep their own threads. */
final class PlanningConversationPolicy {
    static final String KEY = "STORY_PLANNING";
    static final String REVISION = "paired-plain-text-planning-v2";
    static boolean shares(String workflow) { return AgentPromptCatalog.sharesConversation(workflow); }
    private PlanningConversationPolicy() { }
}
