package com.novelagent.writing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ReaderExperienceEntry;
import com.novelagent.writing.domain.ReaderExperienceEvent;
import com.novelagent.writing.domain.ReaderExperienceManuscript;
import com.novelagent.writing.domain.ReaderExperienceMemory;
import com.novelagent.writing.domain.ReaderExperiencePlan;
import com.novelagent.writing.domain.ReaderExperiencePlanInput;
import com.novelagent.writing.domain.ReaderExperienceSource;
import com.novelagent.writing.domain.ReaderExperienceState;
import com.novelagent.writing.domain.ReaderExperienceSubmission;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 伏笔与承诺台账。
 *
 * <p>校验作者计划、来源正文、逐字证据及合法进展状态，事务内追加确认事件。请求指纹支持幂等，版本条件保护手工编辑；未来计划和正史有效性分别展示。</p>
 */
@Service
public class ReaderExperienceService {
    private final ProjectAccessService access;
    private final CurrentActorProvider actor;
    private final ReaderExperienceStore store;
    private final ObjectMapper mapper;

    public ReaderExperienceService(ProjectAccessService access, CurrentActorProvider actor,
            ReaderExperienceStore store, ObjectMapper mapper) {
        this.access = access; this.actor = actor; this.store = store; this.mapper = mapper;
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ReaderExperienceEntry> list(UUID projectId) {
        access.requireOwnedProject(projectId);
        return store.plans(projectId).stream().map(plan -> entry(plan)).toList();
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReaderExperienceEntry get(UUID projectId, UUID id) {
        access.requireOwnedProject(projectId);
        return entry(requirePlan(projectId, id));
    }

    /**
     * 列出可引用的作者已确认正文来源，并保留是否已提交正史及是否被替换的区别。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<ReaderExperienceManuscript> sources(UUID projectId) {
        access.requireOwnedProject(projectId);
        return store.acceptedSources(projectId);
    }

    /**
     * 读取原始或已确认来源资料并保留来源版本，供下载、证据引用或后续业务复核。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param manuscriptId 作为来源或证据引用的正文版本 ID。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReaderExperienceSource source(UUID projectId, UUID manuscriptId) {
        access.requireOwnedProject(projectId);
        return store.source(projectId, manuscriptId).filter(source -> source.status() == ManuscriptStatus.AUTHOR_ACCEPTED)
                .orElseThrow(() -> new WritingResourceNotFoundException("已确认正文", manuscriptId));
    }

    /**
     * 按当前大纲分组已有有效正史摘要，保留未分配章节；不是新模型生成的全书压缩摘要。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ReaderExperienceMemory memory(UUID projectId) {
        access.requireOwnedProject(projectId);
        return store.memory(projectId);
    }

    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param input 本次结构化业务输入或确认命令。
     */
    @Transactional
    public ReaderExperienceEntry create(UUID projectId, ReaderExperiencePlanInput input) {
        if (input == null) throw new IllegalArgumentException("计划不能为空");
        input.validate(false);
        ownAndLock(projectId);
        String hash = hash("create", projectId, input);
        var replay = replay(projectId, input.requestId(), hash);
        if (replay != null) return replay;
        ReaderExperiencePlan plan = store.create(projectId, input);
        store.remember(projectId, input.requestId(), plan.id(), hash, actor.currentUserId());
        return entry(plan);
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @Transactional
    public ReaderExperienceEntry update(UUID projectId, UUID id, ReaderExperiencePlanInput input) {
        if (input == null) throw new IllegalArgumentException("计划不能为空");
        input.validate(true);
        ownAndLock(projectId);
        String hash = hash("update", id, input);
        var replay = replay(projectId, input.requestId(), hash);
        if (replay != null) return replay;
        requireVersion(requirePlan(projectId, id), input.expectedVersion());
        store.update(projectId, id, input);
        store.remember(projectId, input.requestId(), id, hash, actor.currentUserId());
        return entry(requirePlan(projectId, id));
    }

    /**
     * 按预期版本及请求幂等标识软删除计划，保留历史来源与确认事件，不删除正文正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param requestId 客户端请求关联或幂等 ID，具体用途见方法说明。
     */
    @Transactional
    public void delete(UUID projectId, UUID id, long expectedVersion, UUID requestId) {
        if (requestId == null || expectedVersion < 0) throw new IllegalArgumentException("缺少请求标识或有效版本");
        ownAndLock(projectId);
        String hash = hash("delete", id, expectedVersion);
        if (replay(projectId, requestId, hash) != null) return;
        requireVersion(requirePlan(projectId, id), expectedVersion);
        store.advance(projectId, id, expectedVersion, true);
        store.remember(projectId, requestId, id, hash, actor.currentUserId());
    }

    /**
     * 校验作者明确确认、合法状态转换和来源正文连续原文证据后追加台账事件；不会因此将正文提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @Transactional
    public ReaderExperienceEntry submit(UUID projectId, UUID id, ReaderExperienceSubmission input) {
        if (input == null) throw new IllegalArgumentException("提交不能为空");
        input.validate();
        ownAndLock(projectId);
        String hash = hash("submit", id, input);
        var replay = replay(projectId, input.requestId(), hash);
        if (replay != null) return replay;
        ReaderExperiencePlan plan = requirePlan(projectId, id);
        requireVersion(plan, input.expectedVersion());
        ReaderExperienceEntry before = entry(plan);
        before.state().requireTransition(input.state(), before.stale());
        store.lockManuscript(projectId, input.manuscriptId());
        store.lockNames(projectId);
        ReaderExperienceSource source = store.source(projectId, input.manuscriptId())
                .orElseThrow(() -> new WritingResourceNotFoundException("来源正文", input.manuscriptId()));
        source.requireEvidence(projectId, input.manuscriptRowVersion(), input.evidence());
        if (!source.fingerprint().equals(input.sourceFingerprint())) throw new IllegalArgumentException("正文显示或人物姓名已变化，请重新读取来源");
        store.advance(projectId, id, input.expectedVersion(), false);
        store.append(new ReaderExperienceEvent(UUID.randomUUID(), projectId, id, plan.version() + 1, plan,
                input.state(), source.id(), source.rowVersion(), source.chapterNumber(), source.fingerprint(), input.evidence(),
                store.tokenizeEvidence(projectId, input.evidence()),
                input.authorNote() == null ? "" : input.authorNote(), source.chapterCanonCommitId(), source.canon(),
                actor.currentUserId(), "reader-experience-event/1", Instant.now()));
        store.remember(projectId, input.requestId(), id, hash, actor.currentUserId());
        return entry(requirePlan(projectId, id));
    }

    private ReaderExperienceEntry entry(ReaderExperiencePlan plan) {
        List<ReaderExperienceEntry.Evidence> history = new ArrayList<>();
        for (ReaderExperienceEvent event : store.events(plan.projectId(), plan.id())) {
            var source = store.source(plan.projectId(), event.manuscriptId());
            String reason = source.isEmpty() ? "来源正文不存在" : source.get().staleReason(event.manuscriptRowVersion(),
                    event.chapterCanonCommitId(), event.canonAtSubmission(), event.evidence());
            if (source.isPresent() && !source.get().fingerprint().equals(event.sourceFingerprint())) reason = "正文显示或人物姓名已变化";
            history.add(new ReaderExperienceEntry.Evidence(event, reason != null, reason,
                    reason == null && source.isPresent() && source.get().canon()));
        }
        var latest = history.isEmpty() ? null : history.getLast();
        if (latest != null && latest.event().entryVersion() < plan.version()) {
            latest = new ReaderExperienceEntry.Evidence(latest.event(), true, "计划已修改，需作者复核", false);
            history.set(history.size() - 1, latest);
        }
        return new ReaderExperienceEntry(plan, latest == null ? ReaderExperienceState.PLANNED : latest.event().state(),
                latest != null && latest.stale(), List.copyOf(history));
    }

    private void ownAndLock(UUID projectId) {
        access.requireOwnedProject(projectId);
        store.lockProject(projectId);
    }

    private ReaderExperiencePlan requirePlan(UUID projectId, UUID id) {
        return store.plan(projectId, id).filter(plan -> !plan.deleted())
                .orElseThrow(() -> new WritingResourceNotFoundException("读者体验计划", id));
    }

    private void requireVersion(ReaderExperiencePlan plan, long expected) {
        if (plan.version() != expected) throw new ResourceVersionConflictException(expected, plan.version());
    }

    private ReaderExperienceEntry replay(UUID projectId, UUID requestId, String hash) {
        var mutation = store.mutation(projectId, requestId);
        if (mutation.isEmpty()) return null;
        if (!Objects.equals(mutation.get().hash(), hash)) throw new IllegalArgumentException("幂等请求标识已用于不同操作或内容");
        return entry(store.plan(projectId, mutation.get().planId())
                .orElseThrow(() -> new WritingResourceNotFoundException("读者体验计划", mutation.get().planId())));
    }

    private String hash(String operation, UUID resourceId, Object input) {
        try {
            String text = mapper.writeValueAsString(List.of(operation, resourceId, input));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("台账请求无法生成指纹", exception);
        }
    }
}
