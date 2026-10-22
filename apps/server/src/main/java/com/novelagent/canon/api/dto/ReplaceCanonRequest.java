package com.novelagent.canon.api.dto;

import java.util.UUID;

public record ReplaceCanonRequest(UUID reviewVersionId, UUID expectedActiveCommitId, long expectedCanonVersion) {
}
