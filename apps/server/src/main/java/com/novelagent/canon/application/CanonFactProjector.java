package com.novelagent.canon.application;

import com.novelagent.writing.domain.FactProposal;
import java.util.Set;
import java.util.UUID;

/** Projects one already stored fact; participates in the caller's transaction. */
public interface CanonFactProjector {
    Set<String> factTypes();

    default boolean requiresSubject(FactProposal proposal) {
        return true;
    }

    void project(Projection projection);

    void retire(UUID commitId, long replacementVersion);

    record Projection(
            UUID projectId,
            int chapterNumber,
            UUID commitId,
            long canonVersion,
            UUID subjectId,
            UUID factId,
            FactProposal proposal) {
    }
}
