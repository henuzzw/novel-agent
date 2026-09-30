package com.novelagent.canon.api;

import java.util.UUID;

public record ForeshadowResponse(
        UUID id,
        String title,
        String targetEffect,
        String status,
        Integer plannedResolveChapter,
        long canonVersionFrom,
        String evidence) {
}
