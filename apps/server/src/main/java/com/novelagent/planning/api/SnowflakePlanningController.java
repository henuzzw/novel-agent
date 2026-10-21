package com.novelagent.planning.api;

import com.novelagent.planning.application.SnowflakePlanningService;
import com.novelagent.planning.application.ConfirmedSnowflakeService;
import com.novelagent.planning.domain.SnowflakePlan;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 雪花底稿读取与确认接口。生成由导入规划入口负责；确认只固定已有运行并同步规划资料。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/snowflake-plans")
public class SnowflakePlanningController {
    private final SnowflakePlanningService service;
    private final ConfirmedSnowflakeService confirmed;

    public SnowflakePlanningController(
            SnowflakePlanningService service, ConfirmedSnowflakeService confirmed) {
        this.service = service;
        this.confirmed = confirmed;
    }

    /**
     * 确认最新完整成功的雪花运行并同步规划资料；不调用模型或提交正文正史。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param planId 待确认或恢复的雪花运行 ID
     */
    @PostMapping("/{planId}/actions/confirm")
    public StoryBibleResponse confirm(@PathVariable UUID projectId, @PathVariable UUID planId) {
        return confirmed.confirm(projectId, planId);
    }

    /**
     * 读取项目最近一次雪花运行及有效步骤标记，无记录时返回 204。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @GetMapping("/latest")
    public ResponseEntity<SnowflakePlan> latest(@PathVariable UUID projectId) {
        return service.latest(projectId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
