package com.novelagent.planning.api;

import com.novelagent.planning.application.PlanningBatchRunner;
import com.novelagent.planning.application.PlanningBatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 分块规划批次。
 *
 * <p>管理范围批次、逐块生成、失败恢复、取消和确定性组装。每次 runNext 只执行一个块，组装只产生大纲草稿而不发布。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/planning-batches")
public class PlanningBatchController {
    private final PlanningBatchService batches;
    private final PlanningBatchRunner runner;

    public PlanningBatchController(PlanningBatchService batches, PlanningBatchRunner runner) {
        this.batches = batches;
        this.runner = runner;
    }

    public record VersionRequest(@NotNull @PositiveOrZero Long version) { }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<PlanningBatchService.View> list(@PathVariable UUID projectId) {
        return batches.list(projectId);
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @GetMapping("/{id}")
    public PlanningBatchService.View get(@PathVariable UUID projectId, @PathVariable UUID id) {
        return batches.get(projectId, id);
    }

    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param command 本次显式业务命令，包含范围、来源或预期版本。
     */
    @PostMapping
    public ResponseEntity<PlanningBatchService.View> create(@PathVariable UUID projectId,
            @RequestBody PlanningBatchService.CreateCommand command) {
        return ResponseEntity.status(201).body(batches.create(projectId, command));
    }

    /**
     * 显式执行规划批次的下一块，并记录成功或失败；本次调用不自动生成剩余全部块。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param command 本次显式业务命令，包含范围、来源或预期版本。
     */
    @PostMapping("/{id}/actions/run-next")
    public PlanningBatchService.View runNext(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest command) {
        return runner.runNext(projectId, id, command.version());
    }

    /**
     * 请求取消当前任务并按状态约束阻止继续推进；已完成的业务结果不会因此回滚。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param command 本次显式业务命令，包含范围、来源或预期版本。
     */
    @PostMapping("/{id}/actions/cancel")
    public PlanningBatchService.View cancel(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest command) {
        return batches.cancel(projectId, id, command.version());
    }

    /**
     * 按当前来源与状态恢复任务；恢复不是绕过版本校验，也不是无限自动重试授权。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param command 本次显式业务命令，包含范围、来源或预期版本。
     */
    @PostMapping("/{id}/actions/resume")
    public PlanningBatchService.View resume(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest command) {
        return batches.resume(projectId, id, command.version());
    }

    /**
     * 把已完成且范围完整的规划块确定性组装为新大纲草稿，不再调用模型也不自动发布。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param command 本次显式业务命令，包含范围、来源或预期版本。
     */
    @PostMapping("/{id}/actions/assemble")
    public ResponseEntity<OutlineResponse> assemble(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest command) {
        return ResponseEntity.status(201).body(batches.assemble(projectId, id, command.version()));
    }
}
