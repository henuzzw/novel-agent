package com.novelagent.agent.api;

import com.novelagent.agent.application.AutomationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自动创作任务。
 *
 * <p>创建、查询、继续和取消章节范围任务。创建及继续以 202 返回，由业务服务派发后台执行；作者确认门禁仍需另外处理。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/automation-runs")
public class AutomationController {
    private final AutomationService service;

    public AutomationController(AutomationService service) {
        this.service = service;
    }

    /**
     * 创建章节范围任务并返回 202 和任务地址；同一幂等键复用既有任务，自动发布以请求授权为准。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param requestKey 任务创建幂等键，同键请求须保持参数一致
     * @param request 章节范围、轮次和生成额度，以及自动发布与记忆整理授权
     */
    @PostMapping
    public ResponseEntity<AutomationRunResponse> create(
            @PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody CreateAutomationRunRequest request) {
        AutomationRunResponse run = service.create(projectId, requestKey, request);
        return ResponseEntity.accepted()
                .location(
                        URI.create(
                                "/api/v1/projects/" + projectId + "/automation-runs/" + run.id()))
                .body(run);
    }

    /**
     * 列出当前作者项目的章节推进任务及其执行状态。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @GetMapping
    public List<AutomationRunResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    /**
     * 读取指定章节推进任务的进度与失败详情，不启动或重试任务。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param id 目标记录或任务的稳定 ID
     */
    @GetMapping("/{id}")
    public AutomationRunResponse get(@PathVariable UUID projectId, @PathVariable UUID id) {
        return service.get(projectId, id);
    }

    /**
     * 显式恢复符合状态条件的章节推进任务并返回 202；不重置已消耗的生成额度。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param id 目标记录或任务的稳定 ID
     */
    @PostMapping("/{id}/actions/resume")
    public ResponseEntity<AutomationRunResponse> resume(
            @PathVariable UUID projectId, @PathVariable UUID id) {
        return ResponseEntity.accepted().body(service.resume(projectId, id));
    }

    /**
     * 取消范围任务并请求停止当前单章编辑循环，不撤销已经发布的章节。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param id 目标记录或任务的稳定 ID
     */
    @PostMapping("/{id}/actions/cancel")
    public AutomationRunResponse cancel(@PathVariable UUID projectId, @PathVariable UUID id) {
        return service.cancel(projectId, id);
    }
}
