package com.novelagent.agent.application;

import com.novelagent.agent.api.AgentRunResponse;
import com.novelagent.agent.api.AgentRunSummary;
import com.novelagent.agent.api.AgentRunPromptResponse;
import com.novelagent.agent.api.AgentRunOutputResponse;
import org.springframework.beans.factory.annotation.Autowired;
import com.novelagent.agent.api.AgentRunResponse.RequestSnapshotResponse;
import com.novelagent.agent.infrastructure.AgentRunQueryRepository;
import com.novelagent.project.application.ProjectAccessService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模型任务观测。
 *
 * <p>校验项目归属后读取任务、提示词、调用快照及响应。运行中的响应可叠加本进程缓冲区；最终状态以数据库记录为准。</p>
 */
@Service
@Transactional(readOnly = true)
public class AgentRunQueryService {
    private final ProjectAccessService access;
    private final AgentRunQueryRepository runs;
    private final AgentRunOutputBuffer outputs;

    public AgentRunQueryService(ProjectAccessService access, AgentRunQueryRepository runs) {
        this(access, runs, new AgentRunOutputBuffer());
    }

    @Autowired
    public AgentRunQueryService(ProjectAccessService access, AgentRunQueryRepository runs, AgentRunOutputBuffer outputs) {
        this.access = access;
        this.runs = runs;
        this.outputs = outputs;
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    public List<AgentRunResponse> list(UUID projectId) {
        access.requireOwnedProject(projectId);
        return runs.list(projectId);
    }

    /**
     * 按项目汇总调用次数、失败和费用估算，保留真实用量与估算用量的来源差别。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    public AgentRunSummary summary(UUID projectId) {
        access.requireOwnedProject(projectId);
        return runs.summary(projectId);
    }

    /**
     * 读取指定模型任务保存的系统与用户提示词；不存在的项目内任务返回空结果。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param runId 模型调用记录 ID，不是供应商线程 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<AgentRunPromptResponse> prompt(UUID projectId, UUID runId) {
        access.requireOwnedProject(projectId);
        return runs.prompt(projectId, runId);
    }

    /**
     * 读取调用时冻结的参数与用量快照，不使用当前模型设置推断历史调用依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param runId 模型调用记录 ID，不是供应商线程 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<RequestSnapshotResponse> requestSnapshot(UUID projectId, UUID runId) {
        access.requireOwnedProject(projectId);
        return runs.requestSnapshot(projectId, runId);
    }

    /**
     * 读取持久化响应；任务仍 RUNNING 且本进程有增量缓冲时返回当前片段，否则返回数据库终态响应。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param runId 模型调用记录 ID，不是供应商线程 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    public Optional<AgentRunOutputResponse> output(UUID projectId, UUID runId) {
        access.requireOwnedProject(projectId);
        return runs.output(projectId, runId).map(saved -> {
            var live = outputs.get(projectId, runId);
            if (live == null || !"RUNNING".equals(saved.status())) return saved;
            return new AgentRunOutputResponse(saved.id(), saved.status(), live.text(), live.truncated(),
                    saved.errorType(), saved.errorCategory(), saved.errorDetail(), saved.durationMs());
        });
    }
}
