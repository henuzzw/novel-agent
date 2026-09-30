package com.novelagent.planning.application;

import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;

public record GeneratedStoryDirections(
        String generatorType,
        List<StoryDirectionCandidate> directions,
        List<String> questionsForAuthor,
        List<String> changeSummary) {

    public GeneratedStoryDirections(String generatorType, List<StoryDirectionCandidate> directions,
            List<String> questionsForAuthor) {
        this(generatorType, directions, questionsForAuthor, List.of());
    }
}
