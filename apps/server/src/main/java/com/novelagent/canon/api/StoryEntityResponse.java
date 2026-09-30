package com.novelagent.canon.api;

import java.util.UUID;

public record StoryEntityResponse(
        UUID id,
        String type,
        String name,
        String status,
        long canonVersionFrom) {
}
