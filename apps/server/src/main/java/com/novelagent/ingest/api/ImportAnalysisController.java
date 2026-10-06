package com.novelagent.ingest.api;

import com.novelagent.ingest.application.ImportAnalysisRunner;
import com.novelagent.ingest.application.ImportAnalysisStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 原文解析。
 *
 * <p>管理分段解析报告的创建、执行、恢复、取消和作者确认。每项事实或推测需结合原文证据；报告确认不发布圣经、大纲或正史。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/imports/{importId}/analyses")
public class ImportAnalysisController {
    private final ImportAnalysisStore store;
    private final ImportAnalysisRunner runner;
    public ImportAnalysisController(ImportAnalysisStore store, ImportAnalysisRunner runner) { this.store = store; this.runner = runner; }
    public record Version(@NotNull @PositiveOrZero Long version) { }
    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public ResponseEntity<List<ImportAnalysisStore.View>> list(@PathVariable UUID projectId, @PathVariable UUID importId) { return response(store.list(projectId, importId)); }
    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @GetMapping("/{id}")
    public ResponseEntity<ImportAnalysisStore.View> get(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id) { return response(store.get(projectId, importId, id)); }
    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping
    public ResponseEntity<ImportAnalysisStore.View> create(@PathVariable UUID projectId, @PathVariable UUID importId, @RequestBody ImportAnalysisStore.Create input) { return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(store.create(projectId, importId, input)); }
    /**
     * 执行当前任务的下一个待处理阶段或分段；认领、来源复核及结果保存由专责存储服务控制。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/actions/run-next")
    public ResponseEntity<ImportAnalysisStore.View> next(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id, @Valid @RequestBody Version input) { return response(runner.next(projectId, importId, id, input.version())); }
    /**
     * 按当前来源与状态恢复任务；恢复不是绕过版本校验，也不是无限自动重试授权。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/actions/resume")
    public ResponseEntity<ImportAnalysisStore.View> resume(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id, @Valid @RequestBody Version input) { return response(store.action(projectId, importId, id, input.version(), "resume")); }
    /**
     * 请求取消当前任务并按状态约束阻止继续推进；已完成的业务结果不会因此回滚。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/actions/cancel")
    public ResponseEntity<ImportAnalysisStore.View> cancel(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id, @Valid @RequestBody Version input) { return response(store.action(projectId, importId, id, input.version(), "cancel")); }
    /**
     * 要求完整段覆盖及逐项作者决定后确认解析模式；续写禁止 REWORK，来源变化或缺少决定时拒绝。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/actions/confirm")
    public ResponseEntity<ImportAnalysisStore.View> confirm(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id, @RequestBody ImportAnalysisStore.Confirm input) { return response(store.confirm(projectId, importId, id, input)); }
    private static <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
