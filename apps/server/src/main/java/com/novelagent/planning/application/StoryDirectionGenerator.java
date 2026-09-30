package com.novelagent.planning.application;

import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;
import java.util.UUID;

public interface StoryDirectionGenerator {

    ModelProvider provider();

    GeneratedStoryDirections generate(UUID projectId, CreativeIntentSnapshot intent,
            List<StoryDirectionCandidate> previousDirections, String authorInstruction);
}
