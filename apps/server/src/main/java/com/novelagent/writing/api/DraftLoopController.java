package com.novelagent.writing.api;

import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.writing.application.DraftLoopService;
import com.novelagent.writing.application.DraftLoopStore;
import com.novelagent.writing.domain.DraftLoopRun;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 单章自动写作/编辑入口；只返回进度与轮次，不暴露完整来源快照或发布正文。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapter}/draft-loops")
public class DraftLoopController {
    public record Request(
            @NotNull ModelProvider provider, boolean writeFirst, @Min(1) @Max(10) int maxRounds) {}

    public record Response(
            UUID id,
            UUID projectId,
            int chapterNumber,
            ModelProvider provider,
            boolean writeFirst,
            int maxRounds,
            DraftLoopRun.Status status,
            DraftLoopRun.Phase phase,
            DraftLoopRun.StopReason stopReason,
            UUID manuscriptId,
            List<DraftLoopRun.Round> rounds,
            String errorMessage,
            Instant createdAt,
            Instant updatedAt) {
        public static Response from(DraftLoopRun run) {
            return new Response(
                    run.getId(),
                    run.getProjectId(),
                    run.getChapterNumber(),
                    run.getProvider(),
                    run.isWriteFirst(),
                    run.getMaxRounds(),
                    run.getStatus(),
                    run.getPhase(),
                    run.getStopReason(),
                    run.getManuscriptId(),
                    run.getRounds(),
                    run.getErrorMessage(),
                    run.getCreatedAt(),
                    run.getUpdatedAt());
        }
    }

    private final DraftLoopService service;
    private final DraftLoopStore store;

    public DraftLoopController(DraftLoopService service, DraftLoopStore store) {
        this.service = service;
        this.store = store;
    }

    /**
     * 按幂等键创建单章 A/B/C 编辑任务并返回 202；轮次限制为 1 至 10，只允许真实模型。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapter 目标章节号，从 1 开始
     * @param key 单章编辑任务的创建幂等键
     * @param request 真实模型、是否先写正文及检查裁决轮次上限
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Response create(
            @PathVariable UUID projectId,
            @PathVariable int chapter,
            @RequestHeader("Idempotency-Key") UUID key,
            @Valid @RequestBody Request request) {
        return Response.from(
                service.create(
                        projectId,
                        key,
                        chapter,
                        request.provider(),
                        request.writeFirst(),
                        request.maxRounds()));
    }

    /**
     * 读取指定章节的编辑任务与已完成轮次，不返回冻结来源全文。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapter 目标章节号，从 1 开始
     */
    @GetMapping
    public List<Response> list(@PathVariable UUID projectId, @PathVariable int chapter) {
        return store.list(projectId, chapter).stream().map(Response::from).toList();
    }

    /**
     * 核对任务属于当前章节后请求停止；保留已完成轮次及已保存草稿。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapter 目标章节号，从 1 开始
     * @param id 目标记录或任务的稳定 ID
     */
    @PostMapping("/{id}/actions/stop")
    public Response stop(
            @PathVariable UUID projectId, @PathVariable int chapter, @PathVariable UUID id) {
        if (store.get(projectId, id).getChapterNumber() != chapter)
            throw new IllegalArgumentException("自动编辑任务不属于当前章节");
        return Response.from(service.cancel(projectId, id));
    }
}
