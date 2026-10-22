package com.novelagent.canon.api.dto;

import java.util.UUID;

public record StoryEntityResponse(
        UUID id,
        String type,
        String name,
        String status,
        long canonVersionFrom) {
}
