package com.novelagent.canon.application;

import com.novelagent.writing.domain.FactProposal;
import java.util.UUID;

/** Generic facts share the caller's transaction with their typed projections. */
public interface CanonFactStore {
    UUID insert(UUID projectId, UUID commitId, long canonVersion, UUID subjectId, FactProposal proposal);

    void retire(UUID commitId, long replacementVersion);
}
