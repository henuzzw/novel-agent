package com.novelagent.planning.api;

import com.novelagent.planning.application.ConfirmedSnowflakeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 已确认雪花资料的只读接口。返回确切规划快照，不提供独立生成、编辑或发布圣经的流程。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/planning-snapshot")
public class PlanningSnapshotController {
    private final ConfirmedSnowflakeService service;

    public PlanningSnapshotController(ConfirmedSnowflakeService service) {
        this.service = service;
    }

    /**
     * 读取当前作者项目已确认的雪花资料；尚未确认时返回 204，不触发模型生成。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @GetMapping
    public ResponseEntity<StoryBibleResponse> current(@PathVariable UUID projectId) {
        return service.current(projectId)
                .map(
                        value ->
                                ResponseEntity.ok()
                                        .cacheControl(
                                                org.springframework.http.CacheControl.noStore())
                                        .body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
