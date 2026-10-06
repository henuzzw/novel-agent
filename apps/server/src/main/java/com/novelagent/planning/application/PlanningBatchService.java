package com.novelagent.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.api.OutlineResponse;
import com.novelagent.planning.domain.ChapterPlanStatus;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.OutlineWordBudgetPolicy;
import com.novelagent.planning.domain.PlanningBatch;
import com.novelagent.planning.domain.PlanningBatchAssembler;
import com.novelagent.planning.domain.PlanningCheckpoint;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.PlanningBatchJdbcStore;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分块规划批次。
 *
 * <p>冻结圣经、策略、预算和依赖链，控制逐块认领、恢复与组装。只支持未发生正文的全书新规划；组装要求完整连续范围，产生可编辑大纲草稿。</p>
 */
@Service
public class PlanningBatchService {
    private final ProjectAccessService access;
    private final EntityManager entities;
    private final StoryBibleVersionRepository bibles;
    private final CreativeIntentRepository intents;
    private final OutlineVersionRepository outlines;
    private final OutlineWordBudgetPolicy budgets;
    private final CharacterNameService names;
    private final PlanningBatchJdbcStore store;
    private final PlanningCheckpointService checkpoints;
    private final ObjectMapper mapper;

    public PlanningBatchService(ProjectAccessService access, EntityManager entities,
            StoryBibleVersionRepository bibles, CreativeIntentRepository intents,
            OutlineVersionRepository outlines, OutlineWordBudgetPolicy budgets, CharacterNameService names,
            PlanningBatchJdbcStore store, PlanningCheckpointService checkpoints, ObjectMapper mapper) {
        this.access = access;
        this.entities = entities;
        this.bibles = bibles;
        this.intents = intents;
        this.outlines = outlines;
        this.budgets = budgets;
        this.names = names;
        this.store = store;
        this.checkpoints = checkpoints;
        this.mapper = mapper;
    }

    public record CreateCommand(int chapterTo, int chunkSize, ModelProvider provider, String instruction,
            UUID requestId, Long expectedBibleVersion, UUID expectedBibleId) {
        public CreateCommand {
            instruction = instruction == null ? "" : instruction.strip();
            if (chapterTo < 1 || chapterTo > 500 || chunkSize < 1 || chunkSize > 20 || provider == null
                    || provider == ModelProvider.LOCAL_TEMPLATE || instruction.length() > 1000
                    || requestId == null || expectedBibleId == null || expectedBibleVersion == null || expectedBibleVersion < 0) {
                throw new IllegalArgumentException("规划批次范围、真实模型、请求标识或圣经版本不可用");
            }
        }
    }

    public record View(UUID id, UUID projectId, UUID bibleId, long bibleRowVersion, int chapterTo,
            int chunkSize, ModelProvider provider, String instruction, long version, PlanningBatch.Status status,
            UUID outlineVersionId, List<PlanningCheckpoint> checkpoints, Instant createdAt) { }

    public record Claim(long batchVersion, PlanningCheckpoint checkpoint,
            com.novelagent.planning.domain.OutlineWordBudget wordBudget, int chapterCount) { }

    private record Basis(String schema, UUID projectId, String title, UUID bibleId, long bibleRowVersion,
            StoryBibleContent bible, CreativeIntentSnapshot intent, long intentVersion,
            CreativeStrategyPolicy policy, UUID currentOutlineId, long canonVersion, PlanningBatch.Source source) { }

    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param command 本次显式业务命令，包含范围、来源或预期版本。
     */
    @Transactional
    public View create(UUID projectId, CreateCommand command) {
        if (command == null) throw new IllegalArgumentException("规划批次要求不能为空");
        NovelProject project = ownedLocked(projectId);
        var prior = store.byRequest(projectId, command.requestId());
        if (prior.isPresent()) {
            var value = prior.get();
            var source = value.source();
            if (source.chapterTo() != command.chapterTo() || source.chunkSize() != command.chunkSize()
                    || source.provider() != command.provider() || !source.instruction().equals(command.instruction())
                    || source.bibleRowVersion() != command.expectedBibleVersion()
                    || !source.bibleId().equals(command.expectedBibleId())) {
                throw conflict("同一请求标识不能用于不同的规划要求");
            }
            return view(value);
        }
        requireUnwrittenProject(project);
        var bible = currentBible(project);
        if (!bible.getId().equals(command.expectedBibleId())) throw conflict("当前故事圣经已切换，请刷新后创建批次");
        if (bible.getRowVersion() != command.expectedBibleVersion()) {
            throw new ResourceVersionConflictException(command.expectedBibleVersion(), bible.getRowVersion());
        }
        var intent = intents.findById(projectId).orElseThrow(() -> new IllegalArgumentException("项目缺少创作意图"));
        entities.refresh(intent, LockModeType.PESSIMISTIC_READ);
        var source = new PlanningBatch.Source(bible.getId(), bible.getRowVersion(), command.chapterTo(), command.chunkSize(),
                command.provider(), command.instruction(), budgets.plan(intent.getTargetWords()));
        return view(store.insert(projectId, command.requestId(), source, sourceHash(project, bible, source)));
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<View> list(UUID projectId) {
        access.requireOwnedProject(projectId);
        return store.list(projectId).stream().map(this::view).toList();
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true)
    public View get(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        return view(batch(projectId, id, false));
    }

    /**
     * 锁定批次并校验完整前置链后认领下一块，防止重复执行和跳过依赖。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     */
    @Transactional
    public Claim claimNext(UUID projectId, UUID id, long version) {
        NovelProject project = ownedLocked(projectId);
        var batch = batch(projectId, id, true);
        requireVersion(batch, version);
        if (batch.status() != PlanningBatch.Status.READY) throw conflict("批次不能重复执行，请显式恢复失败或取消的批次");
        requireSource(project, batch);
        var values = materialized(batch);
        var prefix = new ArrayList<UUID>();
        PlanningCheckpoint next = null;
        for (var value : values) {
            if (value.status() == PlanningCheckpoint.Status.SUCCEEDED) {
                requirePrefix(value, prefix);
                checkpoints.reuse(projectId, value.id());
                prefix.add(value.id());
            } else {
                if (value != values.getLast() || value.status() != PlanningCheckpoint.Status.PENDING) {
                    throw conflict("未完成片段必须显式恢复后再执行");
                }
                next = value;
            }
        }
        if (next == null) {
            int from = values.isEmpty() ? 1 : values.getLast().chapterTo() + 1;
            if (from > batch.source().chapterTo()) throw conflict("所有片段已经完成，请拼装为大纲草稿");
            int to = Math.min(batch.source().chapterTo(), from + batch.source().chunkSize() - 1);
            next = checkpoints.createDependent(projectId, new PlanningCheckpointService.CreateCommand(
                    "batch:" + id + ":" + from, from, to, batch.source().provider(), batch.source().instruction()), prefix);
        }
        var ids = new ArrayList<>(batch.checkpointIds());
        if (!ids.contains(next.id())) ids.add(next.id());
        change(batch, PlanningBatch.Status.RUNNING, ids, null);
        return new Claim(batch.version() + 1, next, batch.source().wordBudget(), batch.source().chapterTo());
    }

    /**
     * 保存当前认领尝试的结果并推进阶段，复核来源或尝试未变化；迟到输出不能覆盖新尝试。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param claimedVersion 认领时取得的任务行版本，防止旧尝试更新新状态。
     */
    @Transactional
    public View finish(UUID projectId, UUID id, long claimedVersion) {
        NovelProject project = ownedLocked(projectId);
        var batch = batch(projectId, id, true);
        requireRunning(batch, claimedVersion);
        requireSource(project, batch);
        for (var value : materialized(batch)) checkpoints.reuse(projectId, value.id());
        change(batch, PlanningBatch.Status.READY, batch.checkpointIds(), null);
        return view(batch(projectId, id, false));
    }

    /**
     * 记录当前尝试失败，保留可恢复来源及状态；取消或过期尝试不应被旧结果重新激活。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param claimedVersion 认领时取得的任务行版本，防止旧尝试更新新状态。
     */
    @Transactional
    public void fail(UUID projectId, UUID id, long claimedVersion) {
        ownedLocked(projectId);
        var batch = batch(projectId, id, true);
        requireRunning(batch, claimedVersion);
        change(batch, PlanningBatch.Status.FAILED, batch.checkpointIds(), null);
    }

    /**
     * 请求取消当前任务并按状态约束阻止继续推进；已完成的业务结果不会因此回滚。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     */
    @Transactional
    public View cancel(UUID projectId, UUID id, long version) {
        ownedLocked(projectId);
        var batch = batch(projectId, id, true);
        if (batch.status() == PlanningBatch.Status.CANCELLED) return view(batch);
        requireVersion(batch, version);
        if (batch.status() == PlanningBatch.Status.SUCCEEDED) throw conflict("已拼装批次不能取消");
        for (var value : materialized(batch)) {
            if (value.status() != PlanningCheckpoint.Status.SUCCEEDED) {
                checkpoints.cancel(projectId, value.id(), value.version());
            }
        }
        change(batch, PlanningBatch.Status.CANCELLED, batch.checkpointIds(), null);
        return view(batch(projectId, id, false));
    }

    /**
     * 按当前来源与状态恢复任务；恢复不是绕过版本校验，也不是无限自动重试授权。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     */
    @Transactional
    public View resume(UUID projectId, UUID id, long version) {
        NovelProject project = ownedLocked(projectId);
        var batch = batch(projectId, id, true);
        requireVersion(batch, version);
        if (batch.status() != PlanningBatch.Status.CANCELLED && batch.status() != PlanningBatch.Status.FAILED) {
            throw conflict("只有失败或取消的批次可以显式恢复，运行中断请先取消");
        }
        requireSource(project, batch);
        for (var value : materialized(batch)) {
            if (value.status() == PlanningCheckpoint.Status.SUCCEEDED) checkpoints.reuse(projectId, value.id());
            else if (value.status() == PlanningCheckpoint.Status.FAILED || value.status() == PlanningCheckpoint.Status.CANCELLED) {
                checkpoints.retry(projectId, value.id(), value.version());
            } else if (value.status() == PlanningCheckpoint.Status.RUNNING) {
                throw conflict("片段仍在执行，请先取消批次");
            }
        }
        change(batch, PlanningBatch.Status.READY, batch.checkpointIds(), null);
        return view(batch(projectId, id, false));
    }

    /**
     * 把已完成且范围完整的规划块确定性组装为新大纲草稿，不再调用模型也不自动发布。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     */
    @Transactional
    public OutlineResponse assemble(UUID projectId, UUID id, long version) {
        NovelProject project = ownedLocked(projectId);
        var batch = batch(projectId, id, true);
        if (batch.status() == PlanningBatch.Status.SUCCEEDED) {
            var existing = outlines.findByIdAndProjectId(batch.outlineVersionId(), projectId).orElseThrow();
            return response(existing);
        }
        requireVersion(batch, version);
        if (batch.status() != PlanningBatch.Status.READY) throw conflict("批次尚未等待拼装");
        var bible = requireSource(project, batch);
        var values = materialized(batch);
        var prefix = new ArrayList<UUID>();
        for (var value : values) {
            requirePrefix(value, prefix);
            checkpoints.reuse(projectId, value.id());
            prefix.add(value.id());
        }
        var content = PlanningBatchAssembler.assemble(project.getName(),
                names.render(projectId, bible.getContent(), StoryBibleContent.class), batch.source().wordBudget(),
                batch.source().chapterTo(), values);
        int generation = outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)
                .map(value -> Math.addExact(value.getGenerationNumber(), 1)).orElse(1);
        var outline = OutlineVersion.create(UUID.randomUUID(), projectId, generation, "PLANNING_BATCH",
                batch.source().instruction(), bible.getId(), batch.source().wordBudget(), content);
        outlines.saveAndFlush(outline);
        change(batch, PlanningBatch.Status.SUCCEEDED, batch.checkpointIds(), outline.getId());
        return response(outline);
    }

    private NovelProject ownedLocked(UUID projectId) {
        var project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        return project;
    }

    private void requireUnwrittenProject(NovelProject project) {
        if (project.getCurrentCanonVersion() > 0) throw conflict("已有正史的项目请使用原大纲增量调整，不能从第一章重新分块规划");
        if (project.getCurrentOutlineVersionId() != null) {
            var outline = outlines.findByIdAndProjectId(project.getCurrentOutlineVersionId(), project.getId()).orElseThrow();
            if (outline.getContent().arcs().stream().flatMap(arc -> arc.chapters().stream())
                    .anyMatch(chapter -> chapter.status() == ChapterPlanStatus.OCCURRED)) {
                throw conflict("已有已发生章节，请使用原大纲增量调整");
            }
        }
    }

    private StoryBibleVersion currentBible(NovelProject project) {
        if (project.getCurrentBibleVersionId() == null) throw new IllegalArgumentException("请先发布故事圣经");
        var bible = bibles.findByIdAndProjectId(project.getCurrentBibleVersionId(), project.getId()).orElseThrow();
        entities.refresh(bible, LockModeType.PESSIMISTIC_READ);
        if (bible.getStatus() != StoryBibleStatus.PUBLISHED) throw conflict("当前圣经尚未发布");
        return bible;
    }

    private StoryBibleVersion requireSource(NovelProject project, PlanningBatch batch) {
        requireUnwrittenProject(project);
        var bible = currentBible(project);
        if (!batch.sourceHash().equals(sourceHash(project, bible, batch.source()))) {
            throw conflict("圣经、创作意图、策略、姓名或当前大纲已变化，请创建新批次");
        }
        return bible;
    }

    private String sourceHash(NovelProject project, StoryBibleVersion bible, PlanningBatch.Source source) {
        var intent = intents.findById(project.getId()).orElseThrow(() -> new IllegalArgumentException("项目缺少创作意图"));
        entities.refresh(intent, LockModeType.PESSIMISTIC_READ);
        var basis = new Basis("planning-batch/1", project.getId(), project.getName(), bible.getId(), bible.getRowVersion(),
                names.render(project.getId(), bible.getContent(), StoryBibleContent.class), CreativeIntentSnapshot.from(intent),
                intent.getRowVersion(), CreativeStrategyPolicy.from(project), project.getCurrentOutlineVersionId(),
                project.getCurrentCanonVersion(), source);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(basis)));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("规划批次来源不能生成指纹", exception);
        }
    }

    private PlanningBatch batch(UUID projectId, UUID id, boolean lock) {
        return store.find(projectId, id, lock).orElseThrow(() -> new PlanningCheckpointException("规划批次不存在", true));
    }

    private List<PlanningCheckpoint> materialized(PlanningBatch batch) {
        return batch.checkpointIds().stream().map(id -> checkpoints.get(batch.projectId(), id)).toList();
    }

    private View view(PlanningBatch batch) {
        var source = batch.source();
        return new View(batch.id(), batch.projectId(), source.bibleId(), source.bibleRowVersion(), source.chapterTo(),
                source.chunkSize(), source.provider(), source.instruction(), batch.version(), batch.status(),
                batch.outlineVersionId(), materialized(batch), batch.createdAt());
    }

    private OutlineResponse response(OutlineVersion outline) {
        return OutlineResponse.from(outline,
                names.render(outline.getProjectId(), outline.getContent(), com.novelagent.planning.domain.OutlineContent.class));
    }

    private void change(PlanningBatch batch, PlanningBatch.Status status, List<UUID> ids, UUID outlineId) {
        if (!store.transition(batch, status, ids, outlineId)) throw conflict("批次已被其他操作更新");
    }

    private void requirePrefix(PlanningCheckpoint checkpoint, List<UUID> prefix) {
        if (!checkpoint.source().dependencies().stream().map(PlanningCheckpoint.Dependency::checkpointId).toList().equals(prefix)) {
            throw conflict("片段的前置依赖与批次已完成计划不一致");
        }
    }

    private static void requireVersion(PlanningBatch batch, long version) {
        if (batch.version() != version) throw new ResourceVersionConflictException(version, batch.version());
    }

    private static void requireRunning(PlanningBatch batch, long version) {
        requireVersion(batch, version);
        if (batch.status() != PlanningBatch.Status.RUNNING) throw conflict("批次已取消或恢复，晚到执行不能覆盖");
    }

    private static PlanningCheckpointException conflict(String message) {
        return new PlanningCheckpointException(message, false);
    }
}
