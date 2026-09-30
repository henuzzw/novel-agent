package com.novelagent.ingest.api;

import com.novelagent.planning.api.OutlineResponse;
import com.novelagent.planning.api.StoryBibleResponse;

public record ReversePlanResponse(StoryBibleResponse storyBible, OutlineResponse outline) {
}
