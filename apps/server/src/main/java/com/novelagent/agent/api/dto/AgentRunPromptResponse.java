package com.novelagent.agent.api.dto;

import java.util.UUID;

public record AgentRunPromptResponse(UUID id, String systemPrompt, String userPrompt, String promptPreview) { }
