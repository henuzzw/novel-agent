package com.novelagent.ingest.api;

import com.novelagent.ingest.application.ImportedStoryBlueprintService;
import com.novelagent.planning.domain.SnowflakePlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * 导入作品的雪花规划入口。校验请求参数后委托生成或显式恢复，返回底稿供作者确认，不直接发布规划或正文。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/imports/{importId}/actions")
public class ImportedStoryBlueprintController {
    private final ImportedStoryBlueprintService service;

    public ImportedStoryBlueprintController(ImportedStoryBlueprintService service) {
        this.service = service;
    }

    /**
     * 根据已确认的原文解析及改编或续写授权生成雪花底稿，成功结果仍需作者确认。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param importId 当前项目的导入来源 ID
     * @param input 已确认解析 ID 与版本、改编模式、模型及篇幅等生成要求
     */
    @PostMapping("/prepare-snowflake")
    public SnowflakePlan generate(
            @PathVariable UUID projectId,
            @PathVariable UUID importId,
            @Valid @RequestBody PrepareImportedStoryRequest input) {
        return service.generate(projectId, importId, input);
    }

    public record ResumeRequest(@NotNull UUID planId) {}

    /**
     * 显式继续指定导入的最新雪花运行；复核冻结来源后复用有效步骤，不自动换用新资料。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param importId 当前项目的导入来源 ID
     * @param input 当前导入所关联的最新雪花运行 ID
     */
    @PostMapping("/resume-snowflake")
    public SnowflakePlan resume(
            @PathVariable UUID projectId,
            @PathVariable UUID importId,
            @Valid @RequestBody ResumeRequest input) {
        return service.resume(projectId, importId, input.planId());
    }
}
