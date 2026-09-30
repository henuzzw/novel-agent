package com.novelagent.agent.tool;

import com.novelagent.agent.application.AgentStage;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AgentToolPolicy {
    private static final List<NovelToolName> LONG_FORM_WRITING_TOOLS = List.of(
            NovelToolName.GET_RECENT_CHAPTER_SUMMARIES,
            NovelToolName.SEARCH_STORY_MEMORY,
            NovelToolName.GET_RELATED_CANON_FACTS);

    public List<NovelToolName> toolsFor(AgentStage stage) {
        return switch (stage) {
            case CHAPTER_CONTRACT, MANUSCRIPT, CHAPTER_REVIEW -> LONG_FORM_WRITING_TOOLS;
            case STORY_DIRECTION, STORY_BIBLE, OUTLINE -> List.of();
        };
    }
}
