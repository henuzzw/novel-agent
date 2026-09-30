package com.novelagent.canon.api;

import java.util.UUID;

public record CommitCanonRequest(UUID reviewVersionId, long expectedCanonVersion) {
}
