package com.novelagent.planning.api;

import com.novelagent.planning.application.PlanningCheckpointRunner;
import com.novelagent.planning.application.PlanningCheckpointService;
import com.novelagent.planning.domain.PlanningCheckpoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 规划片段。
 *
 * <p>创建、执行、取消、重试或复用连续章节片段。范围与前置依赖由存储服务复核；批次片段不可绕过批次入口独立执行。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/planning-checkpoints")
public class PlanningCheckpointController {
    private final PlanningCheckpointService checkpoints;
    private final PlanningCheckpointRunner runner;
    public PlanningCheckpointController(PlanningCheckpointService checkpoints, PlanningCheckpointRunner runner) {
        this.checkpoints = checkpoints; this.runner = runner;
    }
    public record VersionRequest(@NotNull @PositiveOrZero Long version) { }
    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping public List<PlanningCheckpoint> list(@PathVariable UUID projectId) { return checkpoints.list(projectId); }
    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @GetMapping("/{id}") public PlanningCheckpoint get(@PathVariable UUID projectId, @PathVariable UUID id) { return checkpoints.get(projectId, id); }
    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping public PlanningCheckpoint create(@PathVariable UUID projectId,
            @RequestBody PlanningCheckpointService.CreateCommand request) { return checkpoints.create(projectId, request); }
    /**
     * 执行指定规划片段的当前尝试，按来源及范围保存结果；结果仍是待作者审阅的计划。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/{id}/actions/run") public PlanningCheckpoint run(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest request) { return runner.run(projectId, id, request.version()); }
    /**
     * 请求取消当前任务并按状态约束阻止继续推进；已完成的业务结果不会因此回滚。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/{id}/actions/cancel") public PlanningCheckpoint cancel(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest request) { return checkpoints.cancel(projectId, id, request.version()); }
    /**
     * 显式重开失败任务的执行尝试；保留来源校验并隔离旧尝试结果。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/{id}/actions/retry") public PlanningCheckpoint retry(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest request) { return checkpoints.retry(projectId, id, request.version()); }
    /**
     * 复核冻结圣经、项目策略和依赖后复用成功片段，失败或失效来源不因键相同而继续采用。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @PostMapping("/{id}/actions/reuse") public PlanningCheckpointService.ReusedResult reuse(@PathVariable UUID projectId, @PathVariable UUID id) {
        return checkpoints.reuse(projectId, id);
    }
}
