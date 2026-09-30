package com.novelagent.agent.tool;

import java.util.UUID;

public record NovelToolRequest(
        UUID projectId,
        int chapterNumber,
        long canonVersion,
        String query,
        int semanticCandidateLimit,
        int graphFactCandidateLimit) {
}
