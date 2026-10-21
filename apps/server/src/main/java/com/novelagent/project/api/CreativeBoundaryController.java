package com.novelagent.project.api;

import com.novelagent.project.application.CreativeBoundaryService;
import com.novelagent.project.domain.CreativeBoundaries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * 项目创作边界设置接口。读取世界设定与改编幅度，更新使用项目行版本校验，不直接改写已保存规划。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/settings/creative-boundaries")
public class CreativeBoundaryController {
    private final CreativeBoundaryService service;

    public CreativeBoundaryController(CreativeBoundaryService service) {
        this.service = service;
    }

    public record Update(
            @NotNull CreativeBoundaries.WorldMode worldMode,
            @NotNull CreativeBoundaries.AdaptationScope adaptationScope,
            @NotNull @PositiveOrZero Long version,
            boolean resetAssessment) {}

    /**
     * 读取作者选择及解析评估后的有效创作边界。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @GetMapping
    public CreativeBoundaryService.State get(@PathVariable UUID projectId) {
        return service.get(projectId);
    }

    /**
     * 按项目行版本更新世界设定和改编幅度，可显式清除旧评估；不触发模型调用。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param input 世界模式、改编范围、预期项目行版本及评估重置选项
     */
    @PutMapping
    public CreativeBoundaryService.State update(
            @PathVariable UUID projectId, @Valid @RequestBody Update input) {
        return service.update(
                projectId,
                input.worldMode(),
                input.adaptationScope(),
                input.version(),
                input.resetAssessment());
    }
}
