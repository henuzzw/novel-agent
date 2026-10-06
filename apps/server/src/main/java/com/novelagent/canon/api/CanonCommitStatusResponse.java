package com.novelagent.canon.api;

import java.util.UUID;

public record CanonCommitStatusResponse(boolean committed, UUID activeCommitId,
        UUID activeManuscriptVersionId, long canonVersion) {
}
