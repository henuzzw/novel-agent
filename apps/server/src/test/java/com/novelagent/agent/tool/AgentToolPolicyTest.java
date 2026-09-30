package com.novelagent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.agent.application.AgentStage;
import org.junit.jupiter.api.Test;

class AgentToolPolicyTest {
    private final AgentToolPolicy policy = new AgentToolPolicy();

    @Test
    void onlyWritingStagesReceiveLongTermMemoryTools() {
        assertThat(policy.toolsFor(AgentStage.STORY_DIRECTION)).isEmpty();
        assertThat(policy.toolsFor(AgentStage.STORY_BIBLE)).isEmpty();
        assertThat(policy.toolsFor(AgentStage.OUTLINE)).isEmpty();

        assertThat(policy.toolsFor(AgentStage.CHAPTER_CONTRACT))
                .containsExactly(
                        NovelToolName.GET_RECENT_CHAPTER_SUMMARIES,
                        NovelToolName.SEARCH_STORY_MEMORY,
                        NovelToolName.GET_RELATED_CANON_FACTS);
        assertThat(policy.toolsFor(AgentStage.MANUSCRIPT)).isNotEmpty();
        assertThat(policy.toolsFor(AgentStage.CHAPTER_REVIEW)).isNotEmpty();
    }
}
