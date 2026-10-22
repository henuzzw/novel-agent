package com.novelagent.canon.api.dto;

import java.util.UUID;

public record CommitCanonRequest(UUID reviewVersionId, long expectedCanonVersion) {
}
