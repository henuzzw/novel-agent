package com.novelagent.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record AgentRunResponse(UUID id, String stage, String provider, String status,
            String promptPreview, long inputTokens, long outputTokens, String tokenSource,
            BigDecimal estimatedCost, String currency, Long durationMs, String errorMessage,
            OffsetDateTime startedAt, OffsetDateTime completedAt,
            Long actualInputTokens, Long actualOutputTokens,
            Long estimatedInputTokens, Long estimatedOutputTokens) {
    public AgentRunResponse(UUID id, String stage, String provider, String status,
            String promptPreview, long inputTokens, long outputTokens, String tokenSource,
            BigDecimal estimatedCost, String currency, Long durationMs, String errorMessage,
            OffsetDateTime startedAt, OffsetDateTime completedAt) {
        this(id, stage, provider, status, promptPreview, inputTokens, outputTokens, tokenSource,
                estimatedCost, currency, durationMs, errorMessage, startedAt, completedAt,
                null, null, null, null);
    }

    public record RequestSnapshotResponse(UUID id, JsonNode requestSnapshot, JsonNode usage,
            String tokenSource, Long actualInputTokens, Long actualOutputTokens,
            Long estimatedInputTokens, Long estimatedOutputTokens) { }
}
