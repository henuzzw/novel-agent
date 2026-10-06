package com.novelagent.planning.api;

import com.novelagent.planning.application.StoryDirectionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 故事方向。
 *
 * <p>生成候选方向并由作者显式选中，保存集合中的选择及行版本。选中方向不会自动生成圣经或正文，后续规划读取所选方向的输入快照。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/story-directions")
public class StoryDirectionController {

    private final StoryDirectionService storyDirectionService;

    public StoryDirectionController(StoryDirectionService storyDirectionService) {
        this.storyDirectionService = storyDirectionService;
    }
    ///outlines/actions/generate
    /**
     * 读取创作意图快照与字数预算，在事务外请求候选方向并保存新集合；旧候选是否入模服从生成模式，不自动选择或生成圣经。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/generate")
    public ResponseEntity<StoryDirectionSetResponse> generate(
            @PathVariable UUID projectId,
            @Valid @RequestBody GenerateStoryDirectionsRequest request) {
        StoryDirectionSetResponse response = storyDirectionService.generate(projectId, request);
        return ResponseEntity.created(URI.create(
                        "/api/v1/projects/" + projectId + "/story-directions/" + response.id()))
                .eTag(Long.toString(response.version()))
                .body(response);
    }

    /**
     * 读取最新保存结果；“最新”不自动表示已发布、已确认或已进入正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping("/latest")
    public ResponseEntity<StoryDirectionSetResponse> latest(@PathVariable UUID projectId) {
        return storyDirectionService.latest(projectId)
                .map(response -> ResponseEntity.ok()
                        .eTag(Long.toString(response.version()))
                        .body(response))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 解析 If-Match 后接受作者指定的候选方向，返回新版本 ETag；选择成功不表示圣经已经开始生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param setId 故事方向候选集合 ID。
     * @param ifMatch If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/{setId}/actions/select")
    public ResponseEntity<StoryDirectionSetResponse> select(
            @PathVariable UUID projectId,
            @PathVariable UUID setId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody SelectStoryDirectionRequest request) {
        StoryDirectionSetResponse response = storyDirectionService.select(
                projectId, setId, parseEtag(ifMatch), request.candidateId());
        return ResponseEntity.ok()
                .eTag(Long.toString(response.version()))
                .body(response);
    }

    private static long parseEtag(String value) {
        return Long.parseLong(value.replace("\"", "").trim());
    }
}
