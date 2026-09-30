package com.novelagent.planning.application;

import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.UUID;

public interface StoryBibleGenerator {
    ModelProvider provider();

    GeneratedStoryBible generate(UUID projectId, CreativeIntentSnapshot intent,
            StoryDirectionCandidate direction, StoryBibleContent previousBible, String authorInstruction);
}
