package com.novelagent.agent.application;

import com.novelagent.agent.api.CreateAutomationRunRequest;
import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.agent.infrastructure.AutomationRunRepository;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.application.WritingResourceNotFoundException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AutomationRunStore {
    private final AutomationRunRepository runs;
    private final NovelProjectRepository projects;
    private final OutlineVersionRepository outlines;
    private final CurrentActorProvider actors;
    private final EntityManager entityManager;

    public AutomationRunStore(AutomationRunRepository runs, NovelProjectRepository projects,
            OutlineVersionRepository outlines, CurrentActorProvider actors, EntityManager entityManager) {
        this.runs = runs;
        this.projects = projects;
        this.outlines = outlines;
        this.actors = actors;
        this.entityManager = entityManager;
    }

    @Transactional
    public AutomationRun create(UUID projectId, UUID requestKey, CreateAutomationRunRequest request) {
        NovelProject project = requireOwnedProject(projectId);
        entityManager.lock(project, LockModeType.PESSIMISTIC_WRITE);
        var existing = runs.findByProjectIdAndRequestKey(projectId, requestKey);
        if (existing.isPresent()) {
            AutomationRun run = existing.get();
            ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
            if (run.getFirstChapter() != request.firstChapter() || run.getLastChapter() != request.lastChapter()
                    || run.getProvider() != provider || run.isQualityReviewEnabled() != request.qualityReviewEnabled()
                    || run.getMaxAutoRevisionRounds() != request.maxAutoRevisionRounds()
                    || run.getMaxGenerationSteps() != request.generationStepLimit()
                    || !java.util.Objects.equals(run.getInstruction(), request.instruction())) {
                throw new IllegalArgumentException("同一幂等键不能用于不同任务");
            }
            return run;
        }
        if (runs.findTop50ByProjectIdOrderByCreatedAtDesc(projectId).stream().anyMatch(run ->
                run.getStatus() != AutomationStatus.CANCELLED && run.getStatus() != AutomationStatus.SUCCEEDED)) {
            throw new IllegalStateException("项目已有自动任务，请继续或取消该任务");
        }
        if (project.getCurrentOutlineVersionId() == null) throw new IllegalArgumentException("请先发布大纲");
        var outline = outlines.findByIdAndProjectId(project.getCurrentOutlineVersionId(), projectId)
                .filter(value -> value.getStatus() == OutlineStatus.PUBLISHED)
                .orElseThrow(() -> new IllegalArgumentException("项目当前大纲不可用"));
        if (request.instruction() != null && request.instruction().length() > 2000) {
            throw new IllegalArgumentException("补充要求不能超过 2000 字");
        }
        AutomationRun run = AutomationRun.create(projectId, outline.getId(), requestKey, request.firstChapter(),
                request.lastChapter(), request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider(),
                request.instruction(), request.qualityReviewEnabled(), request.maxAutoRevisionRounds(), request.generationStepLimit());
        Set<Integer> chapterNumbers = outline.getContent().arcs().stream()
                .flatMap(arc -> arc.chapters().stream()).map(chapter -> chapter.number()).collect(Collectors.toSet());
        for (int chapter = request.firstChapter(); chapter <= request.lastChapter(); chapter++) {
            if (!chapterNumbers.contains(chapter)) throw new IllegalArgumentException("当前大纲中不存在第 " + chapter + " 章");
        }
        return runs.saveAndFlush(run);
    }

    @Transactional(readOnly = true)
    public List<AutomationRun> list(UUID projectId) {
        requireOwnedProject(projectId);
        return runs.findTop50ByProjectIdOrderByCreatedAtDesc(projectId);
    }

    @Transactional(readOnly = true)
    public AutomationRun get(UUID projectId, UUID id) {
        requireOwnedProject(projectId);
        return runs.findById(id).filter(run -> run.getProjectId().equals(projectId))
                .orElseThrow(() -> new WritingResourceNotFoundException("自动任务", id));
    }

    @Transactional
    public AutomationRun claim(UUID projectId, UUID id) {
        requireOwnedProject(projectId);
        AutomationRun run = locked(projectId, id);
        run.start(Instant.now());
        return runs.saveAndFlush(run);
    }

    @Transactional
    public Optional<AutomationRun> claimPending(UUID projectId, UUID id) {
        requireOwnedProject(projectId);
        AutomationRun run = locked(projectId, id);
        if (run.getStatus() != AutomationStatus.PENDING) return Optional.empty();
        run.start(Instant.now());
        return Optional.of(runs.saveAndFlush(run));
    }

    @Transactional
    public AutomationRun cancel(UUID projectId, UUID id) {
        requireOwnedProject(projectId);
        AutomationRun run = locked(projectId, id);
        run.cancel();
        return runs.saveAndFlush(run);
    }

    @Transactional
    public boolean update(UUID projectId, UUID id, int attempt, Consumer<AutomationRun> mutation) {
        AutomationRun run = locked(projectId, id);
        if (run.getAttempt() != attempt || run.getStatus() != AutomationStatus.RUNNING) return false;
        mutation.accept(run);
        runs.saveAndFlush(run);
        return run.getStatus() == AutomationStatus.RUNNING;
    }

    @Transactional(readOnly = true)
    public boolean outlineUnchanged(AutomationRun run) {
        return run.getOutlineId().equals(requireOwnedProject(run.getProjectId()).getCurrentOutlineVersionId());
    }

    @Transactional(readOnly = true)
    public boolean chapterOccurred(AutomationRun run) {
        return outlines.findByIdAndProjectId(run.getOutlineId(), run.getProjectId()).orElseThrow()
                .getContent().arcs().stream().flatMap(arc -> arc.chapters().stream())
                .anyMatch(chapter -> chapter.number() == run.getCurrentChapter()
                        && chapter.status() == com.novelagent.planning.domain.ChapterPlanStatus.OCCURRED);
    }

    private AutomationRun locked(UUID projectId, UUID id) {
        return runs.findLocked(projectId, id)
                .orElseThrow(() -> new WritingResourceNotFoundException("自动任务", id));
    }

    private NovelProject requireOwnedProject(UUID projectId) {
        return projects.findById(projectId).filter(project -> project.getOwnerId().equals(actors.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }
}
