package com.novelagent.canon.api;

import java.util.UUID;

public record EntityMentionResponse(UUID id, String proposalId, String role, String mention,
        UUID resolvedEntityId, String resolutionMethod, double confidence, String evidence) {
}
