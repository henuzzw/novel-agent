package com.novelagent.agent.api.dto;

import java.util.UUID;

public record AgentRunOutputResponse(UUID id, String status, String responseText, boolean truncated,
        String errorType, String errorCategory, String errorDetail, Long durationMs) { }
