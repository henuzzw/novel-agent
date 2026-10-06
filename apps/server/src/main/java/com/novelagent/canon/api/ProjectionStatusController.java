package com.novelagent.canon.api;

import com.novelagent.canon.application.ProjectionStatusService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 正史投影状态。
 *
 * <p>查询项目当前正史版本的 Outbox 发布及图谱、向量投影进度。投影是异步可重建副本，进度未完成不等于权威正史提交失败。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/projection-status")
public class ProjectionStatusController {
    private final ProjectionStatusService service;

    public ProjectionStatusController(ProjectionStatusService service) { this.service = service; }

    /**
     * 查询现有业务状态与可得进度，不执行生成、提交或投影。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping
    public ProjectionStatus status(@PathVariable UUID projectId) {
        return service.status(projectId);
    }
}
