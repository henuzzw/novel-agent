package com.novelagent.writing.application;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.domain.ChapterPlanStatus;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.writing.domain.*;
import com.novelagent.writing.infrastructure.DraftLoopRunRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 短事务保护幂等创建、取消、来源复核和版本保存；锁内不调用模型。 */
@Service
public class DraftLoopStore {
    private final DraftLoopRunRepository runs;
    private final ManuscriptVersionRepository manuscripts;
    private final ProjectAccessService access;
    private final DraftLoopContext contexts;
    private final CharacterNameService names;
    private final EntityManager entityManager;
    private final com.novelagent.writing.infrastructure.DraftLoopRecovery recovery;
    private final com.novelagent.agent.infrastructure.AutomationRunRepository automations;

    public DraftLoopStore(DraftLoopRunRepository runs, ManuscriptVersionRepository manuscripts, ProjectAccessService access,
            DraftLoopContext contexts, CharacterNameService names, EntityManager entityManager,
            com.novelagent.writing.infrastructure.DraftLoopRecovery recovery,
            com.novelagent.agent.infrastructure.AutomationRunRepository automations) {
        this.runs = runs; this.manuscripts = manuscripts; this.access = access;
        this.contexts = contexts; this.names = names; this.entityManager = entityManager; this.recovery = recovery;
        this.automations = automations;
    }

    @Transactional
    public DraftLoopRun create(UUID projectId, UUID requestKey, int chapter, com.novelagent.planning.application.ModelProvider provider,
            boolean writeFirst, int maxRounds, DraftLoopContext.Source frozen) {
        lockProject(projectId);
        var existing = runs.findByProjectIdAndRequestKey(projectId, requestKey);
        if (existing.isPresent()) {
            var run = existing.get();
            requireSameRequest(run, chapter, provider, writeFirst, maxRounds);
            return run;
        }
        if (!runs.findByProjectIdAndStatusIn(projectId, List.of(DraftLoopRun.Status.PENDING, DraftLoopRun.Status.RUNNING)).isEmpty()) {
            throw new IllegalStateException("项目已有自动编辑任务，请等待或停止后再开始");
        }
        if (automations.findTop50ByProjectIdOrderByCreatedAtDesc(projectId).stream().anyMatch(value ->
                value.getStatus() == com.novelagent.agent.domain.AutomationStatus.PENDING
                        || value.getStatus() == com.novelagent.agent.domain.AutomationStatus.RUNNING)) {
            throw new IllegalStateException("项目的章节推进任务正在运行，请先停止或等待该任务");
        }
        var current = contexts.capture(projectId, chapter);
        if (!frozen.basis().fingerprint().equals(current.basis().fingerprint()) || !sameManuscript(frozen.latest(), current.latest())) {
            throw new IllegalStateException("准备期间创作依据或正文已变化，请重试");
        }
        if (current.context().chapter().status() == ChapterPlanStatus.OCCURRED) {
            throw new IllegalArgumentException("自动编辑不重写导入作品已发生章节");
        }
        if (!writeFirst && (current.latest() == null || current.latest().getWritingBasis() == null
                || !current.basis().writingBasis().equals(current.latest().getWritingBasis()))) {
            throw new IllegalArgumentException("当前草稿的写作依据已失效，请先依据当前大纲创作");
        }
        var run = DraftLoopRun.create(projectId, requestKey, chapter, provider, writeFirst, maxRounds, frozen.basis(), current.latest());
        run.assignWorker(recovery.identity());
        return runs.saveAndFlush(run);
    }

    /** 幂等重发先读取现有任务，不重复检索资料、冻结上下文或触发模型。创建事务仍再次核对竞态。 */
    @Transactional(readOnly = true)
    public Optional<DraftLoopRun> existing(UUID projectId, UUID key, int chapter,
            com.novelagent.planning.application.ModelProvider provider, boolean writeFirst, int maxRounds) {
        access.requireOwnedProject(projectId);
        var existing = runs.findByProjectIdAndRequestKey(projectId, key);
        existing.ifPresent(run -> requireSameRequest(run, chapter, provider, writeFirst, maxRounds));
        return existing;
    }

    private static void requireSameRequest(DraftLoopRun run, int chapter,
            com.novelagent.planning.application.ModelProvider provider, boolean writeFirst, int maxRounds) {
        if (run.getChapterNumber() != chapter || run.getProvider() != provider || run.isWriteFirst() != writeFirst || run.getMaxRounds() != maxRounds) {
            throw new IllegalArgumentException("同一幂等键不能用于不同自动编辑任务");
        }
    }

    @Transactional(readOnly = true)
    public DraftLoopRun get(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        return runs.findByIdAndProjectId(id, projectId).orElseThrow(() -> new WritingResourceNotFoundException("自动编辑任务", id));
    }

    @Transactional(readOnly = true)
    public List<DraftLoopRun> list(UUID projectId, int chapter) {
        access.requireOwnedProject(projectId);
        return runs.findTop20ByProjectIdAndChapterNumberOrderByCreatedAtDesc(projectId, chapter);
    }

    @Transactional
    public boolean claim(UUID projectId, UUID id) { return locked(projectId, id).claim(); }

    @Transactional
    public DraftLoopRun cancel(UUID projectId, UUID id) {
        var run = locked(projectId, id); run.stop(DraftLoopRun.StopReason.CANCELLED); return run;
    }

    @Transactional
    public void fail(UUID projectId, UUID id, String message) { locked(projectId, id).fail(message); }

    @Transactional
    public boolean ready(UUID projectId, UUID id) { return valid(locked(projectId, id)); }

    @Transactional(readOnly = true)
    public ManuscriptContent body(DraftLoopRun run) {
        access.requireOwnedProject(run.getProjectId());
        return names.render(run.getProjectId(), manuscripts.findByIdAndProjectIdAndChapterNumber(run.getManuscriptId(),
                run.getProjectId(), run.getChapterNumber()).orElseThrow().getContent());
    }

    @Transactional
    public void wrote(UUID projectId, UUID id, GeneratedManuscript output) {
        var run = locked(projectId, id);
        if (!valid(run)) return;
        if (run.getPhase() != DraftLoopRun.Phase.A) throw new IllegalStateException("写作阶段已结束");
        DraftJudgment.requireContent(output.content());
        var saved = saveDraft(run, output.content(), output.changeSummary());
        run.wrote(saved, names.render(projectId, saved.getContent()).body());
    }

    @Transactional
    public void checked(UUID projectId, UUID id, ManuscriptContent before, DraftCheck report) {
        var run = locked(projectId, id);
        if (valid(run)) run.checked(before, report);
    }

    @Transactional
    public void judged(UUID projectId, UUID id, DraftJudgment output) {
        var run = locked(projectId, id);
        if (!valid(run) || !run.judged(output)) return;
        var saved = saveDraft(run, output.content(), output.changeSummary());
        run.revised(saved, names.render(projectId, saved.getContent()).body());
    }

    private ManuscriptVersion saveDraft(DraftLoopRun run, ManuscriptContent content, List<String> changes) {
        int version = manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(run.getProjectId(), run.getChapterNumber())
                .map(value -> value.getVersionNumber() + 1).orElse(1);
        var draft = ManuscriptVersion.create(UUID.randomUUID(), run.getProjectId(), null, run.getChapterNumber(), version,
                run.getProvider().name(), null, run.getManuscriptId(), names.tokenize(run.getProjectId(), content), changes)
                .withWritingBasis(run.getBasis().writingBasis());
        return manuscripts.saveAndFlush(draft);
    }

    /** 来源/当前正文改变或被作者确认即停止，取消之后迟到结果不能产生新稿。 */
    private boolean valid(DraftLoopRun run) {
        if (run.getStatus() != DraftLoopRun.Status.RUNNING) return false;
        DraftLoopContext.Source source;
        try { source = contexts.capture(run.getProjectId(), run.getChapterNumber()); }
        catch (IllegalArgumentException | IllegalStateException changed) { run.stop(DraftLoopRun.StopReason.SOURCE_CHANGED); return false; }
        var latest = source.latest();
        if (latest != null) {
            entityManager.refresh(latest, LockModeType.PESSIMISTIC_WRITE);
        }
        boolean matches = source.basis().fingerprint().equals(run.getBasis().fingerprint())
                && Objects.equals(run.getManuscriptId(), latest == null ? null : latest.getId())
                && (latest == null || latest.getRowVersion() == run.getManuscriptRowVersion());
        if (run.getPhase() != DraftLoopRun.Phase.A && (latest == null || latest.getStatus() != ManuscriptStatus.DRAFT)) matches = false;
        if (!matches) run.stop(DraftLoopRun.StopReason.SOURCE_CHANGED);
        return matches;
    }

    private DraftLoopRun locked(UUID projectId, UUID id) {
        lockProject(projectId);
        var run = get(projectId, id); entityManager.refresh(run, LockModeType.PESSIMISTIC_WRITE); return run;
    }

    private void lockProject(UUID projectId) {
        var project = access.requireOwnedProject(projectId);
        entityManager.refresh(project, LockModeType.PESSIMISTIC_WRITE); access.requireOwnedProject(project);
    }

    private static boolean sameManuscript(ManuscriptVersion left, ManuscriptVersion right) {
        return left == null ? right == null : right != null && left.getId().equals(right.getId()) && left.getRowVersion() == right.getRowVersion();
    }
}
