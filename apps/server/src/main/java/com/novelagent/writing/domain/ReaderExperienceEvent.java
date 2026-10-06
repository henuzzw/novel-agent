package com.novelagent.writing.domain;

import java.time.Instant;
import java.util.UUID;

public record ReaderExperienceEvent(UUID id, UUID projectId, UUID planId, long entryVersion, ReaderExperiencePlan planSnapshot,
        ReaderExperienceState state, UUID manuscriptId, long manuscriptRowVersion, int chapterNumber,
        String sourceFingerprint, String evidence, String evidenceTokenized, String authorNote, UUID chapterCanonCommitId, boolean canonAtSubmission,
        UUID submittedBy, String schemaVersion, Instant createdAt) { }
