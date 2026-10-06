package com.novelagent.agent.api;

import java.math.BigDecimal;

public record AgentRunSummary(long calls, long failures, long inputTokens, long outputTokens,
            BigDecimal estimatedCost, String currency, String tokenSource,
            long actualInputTokens, long actualOutputTokens, long estimatedInputTokens,
            long estimatedOutputTokens, long actualCalls, long estimatedCalls, long unknownCalls) {
    public AgentRunSummary(long calls, long failures, long inputTokens, long outputTokens,
            BigDecimal estimatedCost, String currency, String tokenSource) {
        this(calls, failures, inputTokens, outputTokens, estimatedCost, currency, tokenSource,
                0, 0, inputTokens, outputTokens, 0, calls, 0);
    }
}
