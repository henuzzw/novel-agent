package com.novelagent.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.platform.support.Sha256;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.domain.PlanningCheckpoint;
import com.novelagent.planning.domain.PlanningCheckpointResult;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.PlanningCheckpointJdbcStore;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.NovelProject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规划片段存储。
 *
 * <p>管理来源与依赖指纹、执行尝试及片段状态转换。认领与保存都复核来源，复用必须匹配依据；批次依赖不能凭相同标题或范围猜测。</p>
 */
@Service
public class PlanningCheckpointService {
    private final ProjectAccessService access;
    private final StoryBibleVersionRepository bibles;
    private final PlanningCheckpointJdbcStore store;
    private final EntityManager entities;
    private final ObjectMapper mapper;
    private final CharacterNameService names;

    public PlanningCheckpointService(ProjectAccessService access, StoryBibleVersionRepository bibles,
            PlanningCheckpointJdbcStore store, EntityManager entities, ObjectMapper mapper, CharacterNameService names) {
        this.access = access;
        this.bibles = bibles;
        this.store = store;
        this.entities = entities;
        this.mapper = mapper;
        this.names = names;
    }

    public record CreateCommand(String chunkKey, int chapterFrom, int chapterTo, ModelProvider provider, String instruction) {
        public CreateCommand {
            chunkKey = chunkKey == null ? "" : chunkKey.strip();
            instruction = instruction == null ? "" : instruction.strip();
            if (chunkKey.isBlank() || chunkKey.length() > 128 || provider == null || instruction.length() > 1000
                    || chapterFrom < 1 || chapterTo < chapterFrom || (long) chapterTo - chapterFrom >= 100) {
                throw new IllegalArgumentException("规划分块的范围、标识、供应商或要求不可用");
            }
        }
    }

    public record Claim(PlanningCheckpoint checkpoint, StoryBibleContent bible, List<PlanningCheckpoint> precedingPlans) {
        public Claim(PlanningCheckpoint checkpoint, StoryBibleContent bible) {
            this(checkpoint, bible, List.of());
        }
    }
    public record ReusedResult(UUID checkpointId, long attempt, String dependencyHash, PlanningCheckpointResult result) { }

    private record Basis(String schemaVersion, UUID projectId, UUID bibleId, long bibleRowVersion, StoryBibleContent bible,
            CreativeStrategyPolicy creativeStrategy, ModelProvider provider, String instruction,
            String chunkKey, int chapterFrom, int chapterTo, List<PlanningCheckpoint.Dependency> dependencies) { }

    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param command 本次显式业务命令，包含范围、来源或预期版本。
     */
    @Transactional
    public PlanningCheckpoint create(UUID projectId, CreateCommand command) {
        return createDependent(projectId, command, List.of());
    }

    /**
     * 根据明确前置依赖创建后续规划片段，保存依赖版本及指纹，不按标题猜前置关系。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param command 本次显式业务命令，包含范围、来源或预期版本。
     * @param precedingIds 当前章之前的来源版本 ID，供建立连续性上下文。
     */
    @Transactional
    public PlanningCheckpoint createDependent(UUID projectId, CreateCommand command, List<UUID> precedingIds) {
        if (command == null) throw new IllegalArgumentException("规划分块要求不能为空");
        NovelProject project = ownedLocked(projectId, LockModeType.PESSIMISTIC_WRITE);
        StoryBibleVersion bible = currentBible(project, true);
        var preceding = preceding(project, precedingIds);
        int expectedFrom = 1;
        for (var value : preceding) {
            if (value.chapterFrom() != expectedFrom) throw conflict("前置规划必须从第一章连续覆盖");
            expectedFrom = value.chapterTo() + 1;
        }
        if (!preceding.isEmpty() && command.chapterFrom() != expectedFrom) throw conflict("规划片段与前置范围不连续");
        var dependencies = preceding.stream().map(this::dependency).toList();
        var source = source(project, bible, command, dependencies);
        return store.matching(projectId, command.chunkKey(), source.dependencyHash())
                .orElseGet(() -> store.insert(projectId, command.chunkKey(), command.chapterFrom(), command.chapterTo(), source));
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<PlanningCheckpoint> list(UUID projectId) {
        access.requireOwnedProject(projectId);
        return store.list(projectId);
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true)
    public PlanningCheckpoint get(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        return checkpoint(projectId, id, false);
    }

    /**
     * 在短事务内认领本次执行或修订尝试，校验当前状态、来源和版本；认领不是模型成功。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     */
    @Transactional
    public Claim claim(UUID projectId, UUID id, long expectedVersion) {
        NovelProject project = ownedLocked(projectId, LockModeType.PESSIMISTIC_READ);
        PlanningCheckpoint checkpoint = checkpoint(projectId, id, true);
        requireVersion(checkpoint, expectedVersion);
        if (checkpoint.status() != PlanningCheckpoint.Status.PENDING) throw conflict("规划分块尚未等待执行，不能重复认领");
        StoryBibleVersion bible = requireSource(project, checkpoint);
        change(checkpoint, PlanningCheckpoint.Status.RUNNING, Math.addExact(checkpoint.attempt(), 1), null, null);
        return new Claim(checkpoint(projectId, id, false),
                names.render(projectId, bible.getContent(), StoryBibleContent.class),
                preceding(project, checkpoint.source().dependencies().stream()
                        .map(PlanningCheckpoint.Dependency::checkpointId).toList()));
    }

    /**
     * 保存指定片段当前尝试的生成结果并标记成功，成功不等于大纲已发布。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param attempt 当前执行尝试编号，隔离取消或重试后的旧执行器。
     * @param result 本次操作或模型调用产生的结果。
     */
    @Transactional
    public PlanningCheckpoint succeed(UUID projectId, UUID id, long attempt, PlanningCheckpointResult result) {
        NovelProject project = ownedLocked(projectId, LockModeType.PESSIMISTIC_READ);
        PlanningCheckpoint checkpoint = checkpoint(projectId, id, true);
        requireRunning(checkpoint, attempt);
        requireSource(project, checkpoint);
        if (result == null) throw new IllegalArgumentException("规划分块结果不能为空");
        result.requireRange(checkpoint.chapterFrom(), checkpoint.chapterTo());
        change(checkpoint, PlanningCheckpoint.Status.SUCCEEDED, attempt, result, null);
        return checkpoint(projectId, id, false);
    }

    /**
     * 记录当前尝试失败，保留可恢复来源及状态；取消或过期尝试不应被旧结果重新激活。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param attempt 当前执行尝试编号，隔离取消或重试后的旧执行器。
     * @param reason 修改、失败或取消原因。
     */
    @Transactional
    public PlanningCheckpoint fail(UUID projectId, UUID id, long attempt, String reason) {
        ownedLocked(projectId, LockModeType.PESSIMISTIC_READ);
        PlanningCheckpoint checkpoint = checkpoint(projectId, id, true);
        requireRunning(checkpoint, attempt);
        if (reason == null || reason.isBlank() || reason.length() > 2000) throw new IllegalArgumentException("失败原因不能为空或过长");
        change(checkpoint, PlanningCheckpoint.Status.FAILED, attempt, null, reason.strip());
        return checkpoint(projectId, id, false);
    }

    /**
     * 请求取消当前任务并按状态约束阻止继续推进；已完成的业务结果不会因此回滚。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     */
    @Transactional
    public PlanningCheckpoint cancel(UUID projectId, UUID id, long expectedVersion) {
        ownedLocked(projectId, LockModeType.PESSIMISTIC_READ);
        PlanningCheckpoint checkpoint = checkpoint(projectId, id, true);
        if (checkpoint.status() == PlanningCheckpoint.Status.CANCELLED) return checkpoint;
        requireVersion(checkpoint, expectedVersion);
        if (checkpoint.status() == PlanningCheckpoint.Status.SUCCEEDED) throw conflict("已完成的规划分块不能取消");
        change(checkpoint, PlanningCheckpoint.Status.CANCELLED, checkpoint.attempt(), null, checkpoint.failure());
        return checkpoint(projectId, id, false);
    }

    /**
     * 显式重开失败任务的执行尝试；保留来源校验并隔离旧尝试结果。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     */
    @Transactional
    public PlanningCheckpoint retry(UUID projectId, UUID id, long expectedVersion) {
        NovelProject project = ownedLocked(projectId, LockModeType.PESSIMISTIC_READ);
        PlanningCheckpoint checkpoint = checkpoint(projectId, id, true);
        requireVersion(checkpoint, expectedVersion);
        if (checkpoint.status() != PlanningCheckpoint.Status.FAILED && checkpoint.status() != PlanningCheckpoint.Status.CANCELLED) {
            throw conflict("只有失败或取消的规划分块可显式重试；运行中断请先取消");
        }
        requireSource(project, checkpoint);
        change(checkpoint, PlanningCheckpoint.Status.PENDING, checkpoint.attempt(), null, null);
        return checkpoint(projectId, id, false);
    }

    /**
     * 复核冻结圣经、项目策略和依赖后复用成功片段，失败或失效来源不因键相同而继续采用。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional
    public ReusedResult reuse(UUID projectId, UUID id) {
        NovelProject project = ownedLocked(projectId, LockModeType.PESSIMISTIC_READ);
        PlanningCheckpoint checkpoint = checkpoint(projectId, id, true);
        if (checkpoint.status() != PlanningCheckpoint.Status.SUCCEEDED) throw conflict("规划分块尚未成功完成");
        requireSource(project, checkpoint);
        checkpoint.result().requireRange(checkpoint.chapterFrom(), checkpoint.chapterTo());
        return new ReusedResult(checkpoint.id(), checkpoint.attempt(), checkpoint.source().dependencyHash(), checkpoint.result());
    }

    private NovelProject ownedLocked(UUID projectId, LockModeType mode) {
        NovelProject project = access.requireOwnedProject(projectId);
        entities.refresh(project, mode);
        return project;
    }

    private PlanningCheckpoint checkpoint(UUID projectId, UUID id, boolean lock) {
        return store.find(projectId, id, lock).orElseThrow(() -> new PlanningCheckpointException("规划分块不存在", true));
    }

    private StoryBibleVersion currentBible(NovelProject project, boolean lock) {
        if (project.getCurrentBibleVersionId() == null) throw new IllegalArgumentException("请先发布故事圣经");
        StoryBibleVersion bible = bibles.findByIdAndProjectId(project.getCurrentBibleVersionId(), project.getId())
                .orElseThrow(() -> new IllegalArgumentException("项目当前圣经不可用"));
        if (lock) entities.refresh(bible, LockModeType.PESSIMISTIC_READ);
        if (bible.getStatus() != StoryBibleStatus.PUBLISHED) throw new IllegalArgumentException("规划分块必须使用已发布圣经");
        return bible;
    }

    private StoryBibleVersion requireSource(NovelProject project, PlanningCheckpoint checkpoint) {
        if (!checkpoint.source().bibleId().equals(project.getCurrentBibleVersionId())) {
            throw conflict("规划依据已变化，请基于当前圣经创建新的规划分块");
        }
        StoryBibleVersion bible = currentBible(project, true);
        var command = new CreateCommand(checkpoint.chunkKey(), checkpoint.chapterFrom(), checkpoint.chapterTo(),
                checkpoint.source().provider(), checkpoint.source().instruction());
        var preceding = preceding(project, checkpoint.source().dependencies().stream()
                .map(PlanningCheckpoint.Dependency::checkpointId).toList());
        if (!checkpoint.source().dependencies().equals(preceding.stream().map(this::dependency).toList())
                || !checkpoint.source().dependencyHash().equals(source(project, bible, command,
                        checkpoint.source().dependencies()).dependencyHash())) {
            throw conflict("规划依据已变化，旧结果不能复用或继续执行");
        }
        return bible;
    }

    private PlanningCheckpoint.Source source(NovelProject project, StoryBibleVersion bible, CreateCommand command,
            List<PlanningCheckpoint.Dependency> dependencies) {
        CreativeStrategyPolicy policy = CreativeStrategyPolicy.from(project);
        Basis basis = new Basis(PlanningCheckpoint.SCHEMA_VERSION, project.getId(), bible.getId(), bible.getRowVersion(),
                names.render(project.getId(), bible.getContent(), StoryBibleContent.class), policy,
                command.provider(), command.instruction(), command.chunkKey(),
                command.chapterFrom(), command.chapterTo(), dependencies);
        try {
            String hash = Sha256.ofBytes(mapper.writeValueAsBytes(basis));
            return new PlanningCheckpoint.Source(bible.getId(), bible.getRowVersion(), policy,
                    command.provider(), command.instruction(), hash, dependencies);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("规划依据不能生成指纹", exception);
        }
    }

    private List<PlanningCheckpoint> preceding(NovelProject project, List<UUID> ids) {
        if (ids == null || ids.size() > 500 || ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException("前置规划依赖不可用");
        }
        return ids.stream().map(id -> {
            var value = checkpoint(project.getId(), id, true);
            if (value.status() != PlanningCheckpoint.Status.SUCCEEDED) throw conflict("前置规划尚未成功");
            // The project lock serializes writers; predecessor hashes cover the immutable completed prefix.
            if (!value.source().bibleId().equals(project.getCurrentBibleVersionId())
                    || !value.source().creativeStrategy().equals(CreativeStrategyPolicy.from(project))) {
                throw conflict("前置规划依据已过期");
            }
            value.result().requireRange(value.chapterFrom(), value.chapterTo());
            var command = new CreateCommand(value.chunkKey(), value.chapterFrom(), value.chapterTo(),
                    value.source().provider(), value.source().instruction());
            var bible = currentBible(project, true);
            if (!value.source().dependencyHash().equals(source(project, bible, command,
                    value.source().dependencies()).dependencyHash())) throw conflict("前置规划依据已变化");
            return value;
        }).toList();
    }

    private PlanningCheckpoint.Dependency dependency(PlanningCheckpoint value) {
        try {
            String hash = Sha256.ofBytes(mapper.writeValueAsBytes(value.result()));
            return new PlanningCheckpoint.Dependency(value.id(), value.attempt(), value.source().dependencyHash(), hash);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("前置规划不能生成指纹", exception);
        }
    }

    private void change(PlanningCheckpoint checkpoint, PlanningCheckpoint.Status status, long attempt,
            PlanningCheckpointResult result, String failure) {
        if (!store.transition(checkpoint, status, attempt, result, failure)) throw conflict("规划分块已被取消或由其他执行更新");
    }

    private static void requireRunning(PlanningCheckpoint checkpoint, long attempt) {
        if (attempt <= 0 || checkpoint.status() != PlanningCheckpoint.Status.RUNNING || checkpoint.attempt() != attempt) {
            throw conflict("执行尝试已失效，晚到结果不能覆盖当前规划分块");
        }
    }

    private static void requireVersion(PlanningCheckpoint checkpoint, long version) {
        if (version != checkpoint.version()) throw new ResourceVersionConflictException(version, checkpoint.version());
    }

    private static PlanningCheckpointException conflict(String message) { return new PlanningCheckpointException(message, false); }
}
