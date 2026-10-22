package com.novelagent.canon.api.dto;

public record ProjectionStatus(long canonVersion, boolean kafkaPublished, boolean pgvectorProjected,
            boolean neo4jProjected) {
    }
