package com.novelagent.planning.api;

import com.novelagent.planning.application.OutlineService;
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
 * 分层大纲。
 *
 * <p>区分最新保存版本与项目当前发布版本，提供生成、编辑、历史版本和发布接口。写入使用行版本校验，发布是独立的作者动作。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/outlines")
public class OutlineController {
    private final OutlineService service;
    public OutlineController(OutlineService service) { this.service = service; }

    /**
     * 读取最新保存结果；“最新”不自动表示已发布、已确认或已进入正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping("/latest")
    public ResponseEntity<OutlineResponse> latest(@PathVariable UUID projectId) {
        return service.latest(projectId).map(value -> ResponseEntity.ok().eTag(Long.toString(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    /**
     * 读取项目当前已发布规划指针对应的版本，不能用最新草稿替代正式创作依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping("/current")
    public ResponseEntity<OutlineResponse> current(@PathVariable UUID projectId) {
        return service.current(projectId)
                .map(value -> ResponseEntity.ok().eTag(Long.toString(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    /**
     * 按生成顺序返回历史版本摘要，供作者显式选择基准，不修改当前发布指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<OutlineVersionSummaryResponse> versions(@PathVariable UUID projectId) {
        return service.versions(projectId);
    }
    /**
     * 读取指定版本并限定所属项目；版本 ID 与用于并发编辑的行版本是不同概念。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param outlineId 大纲版本 ID，与生成序号和行版本不同。
     */
    @GetMapping("/{outlineId}")
    public OutlineResponse version(@PathVariable UUID projectId, @PathVariable UUID outlineId) {
        return service.version(projectId, outlineId);
    }
    /**
     * 读取项目当前发布圣经及字数预算、策略，按重新规划或指定基准模式调用模型；保存完整新大纲草稿，不自动替换发布指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/generate")
    public ResponseEntity<OutlineResponse> generate(@PathVariable UUID projectId,
            @Valid @RequestBody GenerateOutlineRequest request) {
        OutlineResponse value = service.generate(projectId, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/outlines/" + value.id()))
                .eTag(Long.toString(value.version())).body(value);
    }
    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param outlineId 大纲版本 ID，与生成序号和行版本不同。
     * @param ifMatch If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PutMapping("/{outlineId}")
    public ResponseEntity<OutlineResponse> update(@PathVariable UUID projectId, @PathVariable UUID outlineId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody UpdateOutlineRequest request) {
        OutlineResponse value = service.update(projectId, outlineId, parse(ifMatch), request.content());
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }
    /**
     * 由作者显式发布指定规划版本，更新项目当前依据并执行该规划对应的资料同步；不提交正文正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param outlineId 大纲版本 ID，与生成序号和行版本不同。
     * @param ifMatch If-Match 请求头中的预期编辑行版本。
     */
    @PostMapping("/{outlineId}/actions/publish")
    public ResponseEntity<OutlineResponse> publish(@PathVariable UUID projectId, @PathVariable UUID outlineId,
            @RequestHeader("If-Match") String ifMatch) {
        OutlineResponse value = service.publish(projectId, outlineId, parse(ifMatch));
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }
    private static long parse(String value) { return Long.parseLong(value.replace("\"", "").trim()); }
}
