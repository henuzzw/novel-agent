package com.novelagent.writing.application;

import com.novelagent.writing.domain.ReaderExperienceEvent;
import com.novelagent.writing.domain.ReaderExperienceManuscript;
import com.novelagent.writing.domain.ReaderExperienceMemory;
import com.novelagent.writing.domain.ReaderExperiencePlan;
import com.novelagent.writing.domain.ReaderExperiencePlanInput;
import com.novelagent.writing.domain.ReaderExperienceSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReaderExperienceStore {
    void lockProject(UUID projectId);
    void lockManuscript(UUID projectId, UUID manuscriptId);
    void lockNames(UUID projectId);
    String tokenizeEvidence(UUID projectId, String evidence);
    List<ReaderExperiencePlan> plans(UUID projectId);
    Optional<ReaderExperiencePlan> plan(UUID projectId, UUID id);
    ReaderExperiencePlan create(UUID projectId, ReaderExperiencePlanInput input);
    void update(UUID projectId, UUID id, ReaderExperiencePlanInput input);
    void advance(UUID projectId, UUID id, long expectedVersion, boolean deleted);
    List<ReaderExperienceEvent> events(UUID projectId, UUID planId);
    void append(ReaderExperienceEvent event);
    Optional<ReaderExperienceSource> source(UUID projectId, UUID manuscriptId);
    List<ReaderExperienceManuscript> acceptedSources(UUID projectId);
    Optional<Mutation> mutation(UUID projectId, UUID requestId);
    void remember(UUID projectId, UUID requestId, UUID planId, String hash, UUID actorId);
    ReaderExperienceMemory memory(UUID projectId);
    record Mutation(UUID planId, String hash) { }
}
