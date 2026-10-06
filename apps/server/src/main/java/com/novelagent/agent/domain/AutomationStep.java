package com.novelagent.agent.domain;

import java.time.Instant;
import java.util.UUID;

public record AutomationStep(int chapterNumber, String stage, String status, UUID artifactId,
        Instant startedAt, Instant completedAt, String errorCode) {
}
