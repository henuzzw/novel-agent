package com.novelagent.canon.api;

import java.util.UUID;

public record StoryEventResponse(
        UUID id,
        String title,
        String summary,
        String storyTime,
        int chapterNumber,
        String importance,
        long canonVersionFrom,
        String evidence) {
}
