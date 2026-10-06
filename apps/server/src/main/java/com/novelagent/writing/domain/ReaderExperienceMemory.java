package com.novelagent.writing.domain;

import java.util.List;
import java.util.UUID;

public record ReaderExperienceMemory(String schemaVersion, String mode, UUID outlineId, Long outlineRowVersion,
        List<Arc> arcs, List<Chapter> unassignedChapters) {
    public record Arc(int number, String title, List<Chapter> chapters) { }
    public record Chapter(int chapterNumber, UUID manuscriptId, long manuscriptRowVersion, String manuscriptSchemaVersion,
            UUID canonCommitId, long canonVersion, String title, String summary) { }
}
