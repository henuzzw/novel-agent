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
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param requestKey 自动任务的幂等键，同键重复请求必须保持参数一致。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping
    public ResponseEntity<AutomationRunResponse> create(@PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody CreateAutomationRunRequest request) {
        AutomationRunResponse run = service.create(projectId, requestKey, request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/projects/" + projectId
                + "/automation-runs/" + run.id())).body(run);
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<AutomationRunResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @GetMapping("/{id}")
    public AutomationRunResponse get(@PathVariable UUID projectId, @PathVariable UUID id) {
        return service.get(projectId, id);
    }

    /**
     * 按当前来源与状态恢复任务；恢复不是绕过版本校验，也不是无限自动重试授权。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @PostMapping("/{id}/actions/resume")
    public ResponseEntity<AutomationRunResponse> resume(@PathVariable UUID projectId, @PathVariable UUID id) {
        return ResponseEntity.accepted().body(service.resume(projectId, id));
    }

    /**
     * 请求取消当前任务并按状态约束阻止继续推进；已完成的业务结果不会因此回滚。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @PostMapping("/{id}/actions/cancel")
    public AutomationRunResponse cancel(@PathVariable UUID projectId, @PathVariable UUID id) {
        return service.cancel(projectId, id);
    }
}
