package com.novelagent.planning.application;

import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;

public record GeneratedStoryBible(String generatorType, StoryBibleContent content, List<String> changeSummary) {
    public GeneratedStoryBible(String generatorType, StoryBibleContent content) {
        this(generatorType, content, List.of());
    }
}
