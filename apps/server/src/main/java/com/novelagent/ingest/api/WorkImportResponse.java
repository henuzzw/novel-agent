package com.novelagent.ingest.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record WorkImportResponse(
        UUID id,
        UUID projectId,
        String originalFilename,
        String mediaType,
        long sizeBytes,
        String sha256,
        String parserVersion,
        String detectedContentType,
        String status,
        String planningStatus,
        String planningMode,
        UUID generatedBibleVersionId,
        UUID generatedOutlineVersionId,
        String planningError,
        List<String> warnings,
        List<ImportedChapterResponse> chapters,
        Instant createdAt,
        Instant confirmedAt) {
}
