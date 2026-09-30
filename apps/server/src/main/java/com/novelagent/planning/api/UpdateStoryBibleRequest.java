package com.novelagent.planning.api;

import com.novelagent.planning.domain.StoryBibleContent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record UpdateStoryBibleRequest(@NotNull @Valid StoryBibleContent content) {
}
