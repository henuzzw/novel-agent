package com.novelagent.agent.api;

import java.util.UUID;

public record AgentRunPromptResponse(UUID id, String systemPrompt, String userPrompt, String promptPreview) { }
