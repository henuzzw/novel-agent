package com.novelagent.canon.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

record CanonProjectionEvent(
        UUID eventId,
        UUID commitId,
        UUID projectId,
        UUID manuscriptVersionId,
        long canonVersion) {

    static CanonProjectionEvent parse(String message, ObjectMapper mapper) throws JsonProcessingException {
        JsonNode event = mapper.readTree(message);
        return new CanonProjectionEvent(
                uuid(event, "eventId"),
                uuid(event, "commitId"),
                uuid(event, "projectId"),
                uuid(event, "manuscriptVersionId"),
                event.path("canonVersion").asLong());
    }

    private static UUID uuid(JsonNode event, String field) {
        return UUID.fromString(event.path(field).asText());
    }
}
