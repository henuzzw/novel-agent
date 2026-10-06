package com.novelagent.planning.api;

import com.novelagent.planning.application.CreationPreparationApprovalService;
import com.novelagent.planning.application.CreationPreparationContextService;
import com.novelagent.planning.application.CreationPreparationRunner;
import com.novelagent.planning.application.CreationPreparationStore;
import com.novelagent.planning.domain.CreationPreparation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 创作准备。
 *
 * <p>管理大纲后的设计、协同、复核任务和显式应用操作。PREPARE 与 REVIEW 范围不同；查看或模型完成不会自动填入正文正史。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/creation-preparations")
public class CreationPreparationController {
    private final CreationPreparationStore store;
    private final CreationPreparationRunner runner;
    private final CreationPreparationApprovalService approval;
    private final CreationPreparationContextService context;
    public CreationPreparationController(CreationPreparationStore store, CreationPreparationRunner runner,
            CreationPreparationApprovalService approval, CreationPreparationContextService context) {
        this.store = store; this.runner = runner; this.approval = approval; this.context = context;
    }
    public record Version(@NotNull @PositiveOrZero Long version) { }
    public record Edit(@NotNull @PositiveOrZero Long version, @NotNull CreationPreparation.World world, @NotNull CreationPreparation.Plot plot) { }
    public record ConfirmLink(@NotNull UUID requestId, @NotNull @PositiveOrZero Long planVersion, boolean authorConfirmed) { }
    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public ResponseEntity<List<CreationPreparationStore.View>> list(@PathVariable UUID projectId) { return noStore(store.list(projectId)); }
    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @GetMapping("/{id}")
    public ResponseEntity<CreationPreparationStore.View> get(@PathVariable UUID projectId, @PathVariable UUID id) { return noStore(store.get(projectId, id)); }
    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping
    public ResponseEntity<CreationPreparationStore.View> create(@PathVariable UUID projectId, @RequestBody CreationPreparationStore.Create input) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(store.create(projectId, input));
    }
    /**
     * 执行当前任务的下一个待处理阶段或分段；认领、来源复核及结果保存由专责存储服务控制。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/actions/run-next")
    public ResponseEntity<CreationPreparationStore.View> next(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Version input) { return noStore(runner.next(projectId, id, input.version())); }
    /**
     * 在同一准备任务上串行推进尚未完成的阶段，遇到等待确认或失败即停止；不跳过作者门禁。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/actions/run-all")
    public ResponseEntity<CreationPreparationStore.View> all(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Version input) { return noStore(runner.all(projectId, id, input.version())); }
    /**
     * 请求取消当前任务并按状态约束阻止继续推进；已完成的业务结果不会因此回滚。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/actions/cancel")
    public ResponseEntity<CreationPreparationStore.View> cancel(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Version input) { return noStore(store.action(projectId, id, input.version(), "cancel")); }
    /**
     * 按当前来源与状态恢复任务；恢复不是绕过版本校验，也不是无限自动重试授权。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/actions/resume")
    public ResponseEntity<CreationPreparationStore.View> resume(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Version input) { return noStore(store.action(projectId, id, input.version(), "resume")); }
    /**
     * 保存作者编辑并遵循源版本及授权范围；上游资料改变后，依赖它的旧检查不能继续当作当前依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PutMapping("/{id}")
    public ResponseEntity<CreationPreparationStore.View> edit(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Edit input) { return noStore(store.edit(projectId, id, input.version(), input.world(), input.plot())); }
    /**
     * 执行本模块明确的作者确认步骤，并核对必要来源、版本与状态；确认不替代其他阶段的发布或正史提交。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/actions/confirm")
    public ResponseEntity<CreationPreparationStore.View> confirm(@PathVariable UUID projectId, @PathVariable UUID id, @RequestBody CreationPreparationApprovalService.Confirm input) { return noStore(approval.confirm(projectId, id, input)); }
    /**
     * 查询复核报告提出的正文事实与计划关联及其有效性；待确认关联不表示台账进度已提交。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/plan-links")
    public ResponseEntity<List<CreationPreparationContextService.Link>> links(@PathVariable UUID projectId) { return noStore(context.links(projectId)); }
    /**
     * 查询剧情单元检查点及相关来源覆盖，不自动运行复核或调整章节。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/checkpoints")
    public ResponseEntity<List<CreationPreparationContextService.Checkpoint>> checkpoints(@PathVariable UUID projectId) { return noStore(context.checkpoints(projectId)); }
    /**
     * 由作者明确确认有效正史事实与计划的关联，并引用来源正文登记台账进度；拒绝来源失效或未明确确认的提交。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/plan-links/{id}/actions/confirm")
    public ResponseEntity<Void> confirmLink(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody ConfirmLink input) {
        context.confirmLink(projectId, id, input.requestId(), input.planVersion(), input.authorConfirmed()); return ResponseEntity.noContent().build();
    }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
