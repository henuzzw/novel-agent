package com.novelagent.planning.application;

import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.util.UUID;

public interface OutlineGenerator {
    ModelProvider provider();
    GeneratedOutline generate(UUID projectId, StoryBibleContent bible, OutlineWordBudget budget,
            OutlineContent previousOutline, String authorInstruction, CreativeStrategyPolicy policy);
}
