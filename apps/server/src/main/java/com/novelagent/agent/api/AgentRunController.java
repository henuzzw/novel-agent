package com.novelagent.agent.api;

import com.novelagent.agent.application.AgentRunQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模型任务观测。
 *
 * <p>提供模型调用列表、用量汇总、完整提示词和冻结请求快照的查询入口。查询不触发生成；完整提示词与请求快照禁止浏览器缓存。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs")
public class AgentRunController {
    private final AgentRunQueryService service;

    public AgentRunController(AgentRunQueryService service) { this.service = service; }

    /**
     * 返回本项目最近 100 次模型任务摘要；完整响应与提示词通过独立接口读取。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<AgentRunResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    /**
     * 按项目汇总调用次数、失败和费用估算，保留真实用量与估算用量的来源差别。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping("/summary")
    public AgentRunSummary summary(@PathVariable UUID projectId) {
        return service.summary(projectId);
    }

    /**
     * 读取指定模型任务保存的系统与用户提示词；项目内任务不存在时返回 404。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param runId 模型调用记录 ID，不是供应商线程 ID。
     */
    @GetMapping("/{runId}/prompt")
    public ResponseEntity<AgentRunPromptResponse> prompt(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return service.prompt(projectId, runId)
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 读取调用时冻结的参数与用量快照，不使用当前模型设置推断历史调用依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param runId 模型调用记录 ID，不是供应商线程 ID。
     */
    @GetMapping("/{runId}/request-snapshot")
    public ResponseEntity<AgentRunResponse.RequestSnapshotResponse> requestSnapshot(
            @PathVariable UUID projectId, @PathVariable UUID runId) {
        return service.requestSnapshot(projectId, runId)
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
