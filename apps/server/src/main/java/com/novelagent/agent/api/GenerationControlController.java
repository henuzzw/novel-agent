package com.novelagent.agent.api;

import com.novelagent.agent.application.GenerationControlRegistry;
import com.novelagent.project.application.ProjectAccessService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 生成停止请求。
 *
 * <p>按请求 ID 或模型任务 ID 停止本进程中准确对应的生成调用。先验证项目归属；返回 STOP_REQUESTED 不表示供应商已经停止，也不撤销完成结果。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class GenerationControlController {
    private final ProjectAccessService access;
    private final GenerationControlRegistry controls;

    public GenerationControlController(ProjectAccessService access, GenerationControlRegistry controls) {
        this.access = access;
        this.controls = controls;
    }

    /**
     * 按请求关联 ID 提交停止请求；尚未登记、已进入保存或不在本进程的调用不能假装已停止。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param requestId 客户端请求关联或幂等 ID，具体用途见方法说明。
     */
    @PostMapping("/generation-requests/{requestId}/actions/stop")
    public StopResponse stopRequest(@PathVariable UUID projectId, @PathVariable UUID requestId) {
        access.requireOwnedProject(projectId);
        controls.stopRequest(projectId, requestId);
        return new StopResponse("STOP_REQUESTED");
    }

    /**
     * 按模型任务 ID 提交停止请求；停止由实际执行器确认，不能撤销已保存的有效结果。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param runId 模型调用记录 ID，不是供应商线程 ID。
     */
    @PostMapping("/agent-runs/{runId}/actions/stop")
    public StopResponse stopRun(@PathVariable UUID projectId, @PathVariable UUID runId) {
        access.requireOwnedProject(projectId);
        controls.stopRun(projectId, runId);
        return new StopResponse("STOP_REQUESTED");
    }

    public record StopResponse(String status) { }
}
