package com.novelagent.project.api;

import com.novelagent.project.application.ProjectService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 小说项目。
 *
 * <p>管理项目创建、本人项目列表、详情及创作意图。意图更新的 ETag 对应意图行版本，不应与项目版本或生成序号混淆。</p>
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /**
     * 校验 IDEA 入口的创作意图，在事务中保存归属当前用户的项目、初始策略及可选意图，不调用模型。
     *
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse project = projectService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + project.id()))
                .eTag(Long.toString(project.version()))
                .body(project);
    }

    /**
     * 按当前用户查询项目并装配其可选创作意图，排序沿用项目更新时间，不返回其他用户项目。
     *
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.list();
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> get(@PathVariable UUID projectId) {
        ProjectResponse project = projectService.get(projectId);
        return ResponseEntity.ok()
                .eTag(Long.toString(project.version()))
                .body(project);
    }

    /**
     * 按 If-Match 的意图行版本保存编辑，返回以新意图版本为依据的 ETag，不能使用项目生成序号。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param ifMatch If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PutMapping("/{projectId}/creative-intent")
    public ResponseEntity<ProjectResponse> updateCreativeIntent(
            @PathVariable UUID projectId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody CreativeIntentRequest request) {
        long expectedVersion = parseEtag(ifMatch);
        ProjectResponse project = projectService.updateCreativeIntent(projectId, expectedVersion, request);
        long intentVersion = project.creativeIntent().version();
        return ResponseEntity.ok()
                .eTag(Long.toString(intentVersion))
                .body(project);
    }

    private static long parseEtag(String value) {
        return Long.parseLong(value.replace("\"", "").trim());
    }
}
