package com.novelagent.project.api;

import com.novelagent.project.application.ProjectCodexModelService;
import com.novelagent.project.domain.CodexModelChoice;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 旧版项目模型入口。
 *
 * <p>保留项目路径的兼容接口，权限校验仍按项目进行。读写实际指向当前用户全局 Codex 模型配置，不是项目独立覆盖。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/settings/codex-model")
public class ProjectCodexModelController {
    private final ProjectCodexModelService service;

    public ProjectCodexModelController(ProjectCodexModelService service) {
        this.service = service;
    }

    /**
     * 校验项目归属后读取当前用户全局 Codex 模型和强度，项目路径不表示项目级覆盖。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping
    public CodexModelChoice get(@PathVariable UUID projectId) {
        return service.get(projectId);
    }

    /**
     * 校验项目和 Codex 能力组合，只替换全局 Codex 模型强度，保留当前供应商和 DeepSeek 模型。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param choice 作者选择的 Codex 模型与强度组合。
     */
    @PutMapping
    public CodexModelChoice update(@PathVariable UUID projectId, @RequestBody CodexModelChoice choice) {
        return service.update(projectId, choice);
    }
}
