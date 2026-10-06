package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ReaderExperienceEvent;
import com.novelagent.writing.domain.ReaderExperienceManuscript;
import com.novelagent.writing.domain.ReaderExperienceMemory;
import com.novelagent.writing.domain.ReaderExperiencePlan;
import com.novelagent.writing.domain.ReaderExperiencePlanInput;
import com.novelagent.writing.domain.ReaderExperienceSource;
import com.novelagent.writing.domain.ReaderExperienceState;
import com.novelagent.writing.domain.ReaderExperienceSubmission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReaderExperienceServiceTest {
    private final UUID project = UUID.randomUUID(), owner = UUID.randomUUID(), manuscript = UUID.randomUUID();
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final MemoryStore store = new MemoryStore();
    private final ReaderExperienceService service = new ReaderExperienceService(access, new CurrentActorProvider(owner),
            store, new ObjectMapper().findAndRegisterModules());

    @BeforeEach void source() {
        store.source = new ReaderExperienceSource(manuscript, project, 2, 1, ManuscriptStatus.AUTHOR_ACCEPTED,
                "她把纸条交给我，我认出了签名。", null, null, null, false);
    }

    @Test void emptyProjectRemainsEmptyAndPlanDoesNotClaimActualProgress() {
        assertThat(service.list(project)).isEmpty();
        var value = service.create(project, planInput(UUID.randomUUID(), null, "找到纸条主人"));
        assertThat(value.state()).isEqualTo(ReaderExperienceState.PLANNED);
        assertThat(value.history()).isEmpty();
        assertThat(value.stale()).isFalse();
    }

    @Test void submittedEvidenceIsAuthorGatedAndIdempotentEvenAfterLaterVersions() {
        var plan = service.create(project, planInput(UUID.randomUUID(), null, "找到纸条主人"));
        var input = submission(UUID.randomUUID(), plan.plan().version(), ReaderExperienceState.SET_UP, true, "纸条");
        var first = service.submit(project, plan.plan().id(), input);
        assertThat(first.history()).hasSize(1);
        assertThat(first.history().getFirst().canon()).isFalse();
        assertThat(first.history().getFirst().event().submittedBy()).isEqualTo(owner);
        assertThat(first.history().getFirst().event().planSnapshot()).isEqualTo(plan.plan());
        assertThat(service.submit(project, plan.plan().id(), input)).isEqualTo(first);
        assertThat(store.history).hasSize(1);
        assertThatThrownBy(() -> service.submit(project, plan.plan().id(), submission(input.requestId(), 0,
                ReaderExperienceState.SET_UP, true, "签名"))).hasMessageContaining("幂等");
        service.update(project, plan.plan().id(), planInput(UUID.randomUUID(), 1L, "解释签名"));
        assertThat(service.submit(project, plan.plan().id(), input).stale()).isTrue();
        assertThat(store.history).hasSize(1);
    }

    @Test void rejectsUnconfirmedWrongVersionWrongProjectAndInvalidEvidenceWithoutAdvancing() {
        var plan = service.create(project, planInput(UUID.randomUUID(), null, "找到纸条主人"));
        UUID id = plan.plan().id();
        assertThatThrownBy(() -> service.submit(project, id, submission(UUID.randomUUID(), 0, ReaderExperienceState.SET_UP, false, "纸条")))
                .hasMessageContaining("作者明确确认");
        assertThatThrownBy(() -> service.submit(project, id, submission(UUID.randomUUID(), 4, ReaderExperienceState.SET_UP, true, "纸条")))
                .isInstanceOf(ResourceVersionConflictException.class);
        assertThatThrownBy(() -> service.submit(project, id, submission(UUID.randomUUID(), 0, ReaderExperienceState.PAYOFF, true, "纸条")))
                .hasMessageContaining("无效状态迁移");
        assertThatThrownBy(() -> service.submit(project, id, submission(UUID.randomUUID(), 0, ReaderExperienceState.SET_UP, true, "不存在的证据")))
                .hasMessageContaining("逐字");
        assertThatThrownBy(() -> service.submit(UUID.randomUUID(), id, submission(UUID.randomUUID(), 0, ReaderExperienceState.SET_UP, true, "纸条")))
                .isInstanceOf(WritingResourceNotFoundException.class);
        store.source = new ReaderExperienceSource(manuscript, UUID.randomUUID(), 2, 1, ManuscriptStatus.AUTHOR_ACCEPTED,
                "纸条", null, null, null, false);
        assertThatThrownBy(() -> service.submit(project, id, submission(UUID.randomUUID(), 0, ReaderExperienceState.SET_UP, true, "纸条")))
                .hasMessageContaining("不属于当前项目");
        assertThat(store.plans.get(id).version()).isZero();
        assertThat(store.history).isEmpty();
    }

    @Test void sourceChangesAndPlanEditsFlagStaleRatherThanInventingNewStates() {
        var plan = service.create(project, planInput(UUID.randomUUID(), null, "找到纸条主人"));
        UUID id = plan.plan().id();
        service.submit(project, id, submission(UUID.randomUUID(), 0, ReaderExperienceState.SET_UP, true, "纸条"));
        store.source = new ReaderExperienceSource(manuscript, project, 3, 1, ManuscriptStatus.AUTHOR_ACCEPTED,
                "纸条", null, null, null, false);
        var stale = service.get(project, id);
        assertThat(stale.state()).isEqualTo(ReaderExperienceState.SET_UP);
        assertThat(stale.stale()).isTrue();
        assertThat(stale.history().getFirst().staleReason()).contains("已变化");
        source();
        service.update(project, id, planInput(UUID.randomUUID(), 1L, "新的承诺"));
        assertThat(service.get(project, id).history().getLast().staleReason()).contains("计划已修改");
        var renewed = service.submit(project, id, submission(UUID.randomUUID(), 2, ReaderExperienceState.SET_UP, true, "签名"));
        assertThat(renewed.stale()).isFalse();
        assertThat(renewed.history()).hasSize(2);
    }

    @Test void changedRenderedNamesExpireStoredQuotesAndRejectStaleDisplayFingerprints() {
        var plan = service.create(project, planInput(UUID.randomUUID(), null, "找到纸条主人"));
        UUID id = plan.plan().id();
        String originalFingerprint = store.source.fingerprint();
        service.submit(project, id, submission(UUID.randomUUID(), 0, ReaderExperienceState.SET_UP, true, "纸条"));
        store.source = new ReaderExperienceSource(manuscript, project, 2, 1, ManuscriptStatus.AUTHOR_ACCEPTED,
                "林安把纸条交给我，我认出了签名。", null, null, null, false);
        assertThat(service.get(project, id).history().getLast().staleReason()).contains("人物姓名已变化");
        var input = new ReaderExperienceSubmission(UUID.randomUUID(), 1L, ReaderExperienceState.SET_UP,
                manuscript, 2L, originalFingerprint, "纸条", "重新绑定", true);
        assertThatThrownBy(() -> service.submit(project, id, input)).hasMessageContaining("姓名已变化");
        assertThat(store.history).hasSize(1);
        assertThat(service.get(project, id).history().getFirst().event().evidence()).isEqualTo("纸条");
    }

    @Test void deleteIsVersionedIdempotentAndPreservesEvidenceHistory() {
        UUID key = UUID.randomUUID();
        var input = planInput(key, null, "找到纸条主人");
        var first = service.create(project, input);
        assertThat(service.create(project, input)).isEqualTo(first);
        assertThat(store.plans).hasSize(1);
        UUID id = first.plan().id();
        service.submit(project, id, submission(UUID.randomUUID(), 0, ReaderExperienceState.OPEN, true, "纸条"));
        assertThatThrownBy(() -> service.delete(project, id, 0, UUID.randomUUID())).isInstanceOf(ResourceVersionConflictException.class);
        UUID deletion = UUID.randomUUID();
        service.delete(project, id, 1, deletion);
        service.delete(project, id, 1, deletion);
        assertThat(service.list(project)).isEmpty();
        assertThat(store.history).hasSize(1);
        assertThatThrownBy(() -> service.get(project, id)).isInstanceOf(WritingResourceNotFoundException.class);
    }

    @Test void allReadsAndWritesRequireOwnedProjectBeforeStorageAccess() {
        doThrow(new ProjectNotFoundException(project)).when(access).requireOwnedProject(project);
        assertThatThrownBy(() -> service.list(project)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.memory(project)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.sources(project)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.create(project, planInput(UUID.randomUUID(), null, "承诺"))).isInstanceOf(ProjectNotFoundException.class);
        assertThat(store.plans).isEmpty();
    }

    private ReaderExperiencePlanInput planInput(UUID key, Long version, String promise) {
        return new ReaderExperiencePlanInput(key, version, ReaderExperiencePlan.Kind.FORESHADOW, "纸条", promise, "签名", "认出主人", "新的选择", 3);
    }

    private ReaderExperienceSubmission submission(UUID key, long version, ReaderExperienceState state, boolean confirmed, String quote) {
        return new ReaderExperienceSubmission(key, version, state, manuscript, 2L, store.source.fingerprint(), quote, "作者确认", confirmed);
    }

    private static class MemoryStore implements ReaderExperienceStore {
        final Map<UUID, ReaderExperiencePlan> plans = new LinkedHashMap<>();
        final List<ReaderExperienceEvent> history = new ArrayList<>();
        final Map<String, Mutation> mutations = new HashMap<>();
        ReaderExperienceSource source;
        public void lockProject(UUID project) { }
        public void lockManuscript(UUID project, UUID id) { }
        public void lockNames(UUID project) { }
        public String tokenizeEvidence(UUID project, String evidence) { return evidence; }
        public List<ReaderExperiencePlan> plans(UUID project) { return plans.values().stream().filter(p -> p.projectId().equals(project) && !p.deleted()).toList(); }
        public Optional<ReaderExperiencePlan> plan(UUID project, UUID id) { return Optional.ofNullable(plans.get(id)).filter(p -> p.projectId().equals(project)); }
        public ReaderExperiencePlan create(UUID project, ReaderExperiencePlanInput input) {
            UUID id = UUID.randomUUID();
            var p = value(id, project, input, 0, false);
            plans.put(id, p); return p;
        }
        public void update(UUID project, UUID id, ReaderExperiencePlanInput input) { plans.put(id, value(id, project, input, plans.get(id).version() + 1, false)); }
        public void advance(UUID project, UUID id, long version, boolean deleted) {
            var p = plans.get(id);
            plans.put(id, new ReaderExperiencePlan(id, project, p.kind(), p.title(), p.promise(), p.setup(), p.payoff(), p.aftermath(),
                    p.plannedChapter(), version + 1, p.schemaVersion(), deleted, p.createdAt(), p.updatedAt()));
        }
        public List<ReaderExperienceEvent> events(UUID project, UUID id) { return history.stream().filter(e -> e.projectId().equals(project) && e.planId().equals(id)).toList(); }
        public void append(ReaderExperienceEvent event) { history.add(event); }
        public Optional<ReaderExperienceSource> source(UUID project, UUID id) { return Optional.ofNullable(source).filter(s -> s.id().equals(id)); }
        public List<ReaderExperienceManuscript> acceptedSources(UUID project) { return List.of(); }
        public Optional<Mutation> mutation(UUID project, UUID key) { return Optional.ofNullable(mutations.get(project + ":" + key)); }
        public void remember(UUID project, UUID key, UUID id, String hash, UUID actor) { mutations.put(project + ":" + key, new Mutation(id, hash)); }
        public ReaderExperienceMemory memory(UUID project) { return new ReaderExperienceMemory("reader-experience-memory/1", "EXISTING_CANON_SUMMARIES", null, null, List.of(), List.of()); }
        private ReaderExperiencePlan value(UUID id, UUID project, ReaderExperiencePlanInput i, long version, boolean deleted) {
            return new ReaderExperiencePlan(id, project, i.kind(), i.title(), i.promise(), i.setup(), i.payoff(), i.aftermath(),
                    i.plannedChapter(), version, "reader-experience/1", deleted, Instant.EPOCH, Instant.EPOCH);
        }
    }
}
