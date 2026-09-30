package com.novelagent.ingest.api;

import java.util.UUID;

public record ImportedChapterResponse(
        UUID id,
        int ordinal,
        String title,
        String content,
        int characterCount,
        String contentType,
        boolean selected) {
}
