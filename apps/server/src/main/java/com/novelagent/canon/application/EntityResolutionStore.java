package com.novelagent.canon.application;

import java.util.List;
import java.util.UUID;

/** Storage lookups return candidates; the application decides whether a match is unambiguous. */
public interface EntityResolutionStore {
    boolean containsActive(UUID projectId, UUID entityId, String type);

    List<UUID> canonicalMatches(UUID projectId, String type, String name);

    List<UUID> aliasMatches(UUID projectId, String type, String name);

    UUID create(UUID projectId, UUID commitId, long canonVersion, String type, String name, String evidence);

    void recordMention(Mention mention);

    record Mention(
            UUID projectId,
            UUID commitId,
            String proposalId,
            String role,
            String text,
            UUID requestedId,
            UUID resolvedId,
            String method,
            double confidence,
            String evidence) {
    }
}
