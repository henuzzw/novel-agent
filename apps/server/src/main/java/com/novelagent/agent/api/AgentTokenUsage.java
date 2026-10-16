package com.novelagent.agent.api;

import java.time.OffsetDateTime;

/** Project-only usage snapshot; periods use invocation start time, not the account quota window. */
public record AgentTokenUsage(OffsetDateTime asOf, OffsetDateTime recentFrom,
        AgentRunSummary projectTotal, AgentRunSummary lastFiveHours) { }
