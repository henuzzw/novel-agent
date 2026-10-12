package com.novelagent.agent.tool;

import java.util.UUID;
import java.util.List;

public record NovelToolRequest(
        UUID projectId,
        int chapterNumber,
        long canonVersion,
        String query,
        int semanticCandidateLimit,
        int graphFactCandidateLimit,
        UUID planningVersionId,
        List<String> keywords) {
    public NovelToolRequest {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    public NovelToolRequest(UUID projectId, int chapterNumber, long canonVersion, String query,
            int semanticCandidateLimit, int graphFactCandidateLimit) {
        this(projectId, chapterNumber, canonVersion, query, semanticCandidateLimit, graphFactCandidateLimit,
                null, List.of());
    }
}
