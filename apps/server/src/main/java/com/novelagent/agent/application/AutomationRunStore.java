package com.novelagent.agent.application;

import com.novelagent.agent.api.CreateAutomationRunRequest;
import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.agent.infrastructure.AutomationRunRepository;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.NovelProject;
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

/**
 * 自动创作任务。
 *
 * <p>以短事务保存任务、认领执行尝试和更新进度。项目锁与任务锁保护幂等创建、并发认领；attempt 隔离取消或重试后的旧执行器，不在这里调用模型。</p>
 */
@Service
public class AutomationRunStore {
    private final AutomationRunRepository runs;
    private final OutlineVersionRepository outlines;
    private final ProjectAccessService access;
    private final EntityManager entityManager;

    public AutomationRunStore(
            AutomationRunRepository runs,
            OutlineVersionRepository outlines,
            ProjectAccessService access,
            EntityManager entityManager) {
        this.runs = runs;
        this.outlines = outlines;
        this.access = access;
        this.entityManager = entityManager;
    }

    /**
     * 项目锁内校验已发布大纲、章节范围和全部任务参数，按请求键幂等创建；同键不同配置或未结束的已有任务拒绝创建。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param requestKey 自动任务的幂等键，同键重复请求必须保持参数一致。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @Transactional
    public AutomationRun create(UUID projectId, UUID requestKey, CreateAutomationRunRequest request) {
        NovelProject project = access.requireOwnedProject(projectId);
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

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<AutomationRun> list(UUID projectId) {
        access.requireOwnedProject(projectId);
        return runs.findTop50ByProjectIdOrderByCreatedAtDesc(projectId);
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true)
    public AutomationRun get(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        return runs.findById(id).filter(run -> run.getProjectId().equals(projectId))
                .orElseThrow(() -> new WritingResourceNotFoundException("自动任务", id));
    }

    /**
     * 在短事务内认领本次执行或修订尝试，校验当前状态、来源和版本；认领不是模型成功。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional
    public AutomationRun claim(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        AutomationRun run = locked(projectId, id);
        run.start(Instant.now());
        return runs.saveAndFlush(run);
    }

    /**
     * 仅认领仍为 PENDING 的自动任务，已被其他执行器处理时返回空，避免重复派发。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional
    public Optional<AutomationRun> claimPending(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        AutomationRun run = locked(projectId, id);
        if (run.getStatus() != AutomationStatus.PENDING) return Optional.empty();
        run.start(Instant.now());
        return Optional.of(runs.saveAndFlush(run));
    }

    /**
     * 请求取消当前任务并按状态约束阻止继续推进；已完成的业务结果不会因此回滚。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional
    public AutomationRun cancel(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        AutomationRun run = locked(projectId, id);
        run.cancel();
        return runs.saveAndFlush(run);
    }

    /**
     * 仅当前 RUNNING 且 attempt 匹配时执行进度变更，返回变更后是否仍运行；旧执行器写入会被忽略。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param attempt 当前执行尝试编号，隔离取消或重试后的旧执行器。
     * @param mutation 仅供当前已认领自动任务执行的状态变更操作。
     */
    @Transactional
    public boolean update(UUID projectId, UUID id, int attempt, Consumer<AutomationRun> mutation) {
        AutomationRun run = locked(projectId, id);
        if (run.getAttempt() != attempt || run.getStatus() != AutomationStatus.RUNNING) return false;
        mutation.accept(run);
        runs.saveAndFlush(run);
        return run.getStatus() == AutomationStatus.RUNNING;
    }

    /**
     * 比较任务冻结的大纲 ID 与项目当前发布大纲指针，指针变化时不能继续使用旧任务依据。
     *
     * @param run 已读取或认领的自动任务快照。
     */
    @Transactional(readOnly = true)
    public boolean outlineUnchanged(AutomationRun run) {
        return run.getOutlineId().equals(access.requireOwnedProject(run.getProjectId()).getCurrentOutlineVersionId());
    }

    /**
     * 检查任务大纲中的当前章是否已标记 OCCURRED，防止自动新创作改写已发生章节。
     *
     * @param run 已读取或认领的自动任务快照。
     */
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

}
