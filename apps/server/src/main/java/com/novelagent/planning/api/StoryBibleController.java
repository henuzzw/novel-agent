package com.novelagent.planning.api;

import com.novelagent.planning.application.StoryBibleService;
import com.novelagent.planning.application.CharacterBlueprintCompletionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import java.util.List;
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
 * 故事圣经。
 *
 * <p>管理生成、编辑、手动修订、人物补全和发布。current 指已发布依据，latest 可能是待确认草稿；发布才触发规划资料同步。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/story-bibles")
public class StoryBibleController {
    private final StoryBibleService service;
    private final CharacterBlueprintCompletionService characters;

    public StoryBibleController(StoryBibleService service, CharacterBlueprintCompletionService characters) {
        this.service = service; this.characters = characters;
    }

    /**
     * 读取最新保存结果；“最新”不自动表示已发布、已确认或已进入正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping("/latest")
    public ResponseEntity<StoryBibleResponse> latest(@PathVariable UUID projectId) {
        return service.latest(projectId).map(value -> ResponseEntity.ok().eTag(Long.toString(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 读取项目当前已发布规划指针对应的版本，不能用最新草稿替代正式创作依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping("/current")
    public ResponseEntity<StoryBibleResponse> current(@PathVariable UUID projectId) {
        return service.current(projectId).map(value -> ResponseEntity.ok().eTag(Long.toString(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 按生成顺序返回历史版本摘要，供作者显式选择基准，不修改当前发布指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<StoryBibleVersionSummaryResponse> versions(@PathVariable UUID projectId) {
        return service.versions(projectId);
    }

    /**
     * 读取指定版本并限定所属项目；版本 ID 与用于并发编辑的行版本是不同概念。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param versionId 所读取或操作的产物版本 ID。
     */
    @GetMapping("/{versionId}")
    public StoryBibleResponse version(@PathVariable UUID projectId, @PathVariable UUID versionId) {
        return service.version(projectId, versionId);
    }

    /**
     * 按创作意图和选中方向新生成圣经，或在明确基准版本上有限修订；模型前后复核主要依据，保存草稿供作者发布。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/generate")
    public ResponseEntity<StoryBibleResponse> generate(@PathVariable UUID projectId,
            @Valid @RequestBody GenerateStoryBibleRequest request) {
        StoryBibleResponse value = service.generate(projectId, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/story-bibles/" + value.id()))
                .eTag(Long.toString(value.version())).body(value);
    }

    /**
     * 基于作者选定的已发布或确认来源创建可编辑修订草稿，保持原版本与来源关联，不调用模型。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param versionId 所读取或操作的产物版本 ID。
     * @param ifMatch If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/{versionId}/actions/create-revision")
    public ResponseEntity<StoryBibleResponse> createRevision(@PathVariable UUID projectId,
            @PathVariable UUID versionId, @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody UpdateStoryBibleRequest request) {
        StoryBibleResponse value = service.createRevision(projectId, versionId,
                parseEtag(ifMatch), request.content());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/story-bibles/" + value.id()))
                .eTag(Long.toString(value.version())).body(value);
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param versionId 所读取或操作的产物版本 ID。
     * @param ifMatch If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PutMapping("/{versionId}")
    public ResponseEntity<StoryBibleResponse> update(@PathVariable UUID projectId, @PathVariable UUID versionId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody UpdateStoryBibleRequest request) {
        StoryBibleResponse value = service.update(projectId, versionId, parseEtag(ifMatch), request.content());
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }

    /**
     * 由作者显式发布指定规划版本，更新项目当前依据并执行该规划对应的资料同步；不提交正文正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param versionId 所读取或操作的产物版本 ID。
     * @param ifMatch If-Match 请求头中的预期编辑行版本。
     */
    @PostMapping("/{versionId}/actions/publish")
    public ResponseEntity<StoryBibleResponse> publish(@PathVariable UUID projectId, @PathVariable UUID versionId,
            @RequestHeader("If-Match") String ifMatch) {
        StoryBibleResponse value = service.publish(projectId, versionId, parseEtag(ifMatch));
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }

    /**
     * 在指定圣经及 If-Match 行版本上请求人物补全，返回新 DRAFT；独立人物档案需发布或同步有效规划后补入。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param versionId 所读取或操作的产物版本 ID。
     * @param ifMatch If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/{versionId}/actions/complete-characters")
    public ResponseEntity<StoryBibleResponse> completeCharacters(@PathVariable UUID projectId,
            @PathVariable UUID versionId, @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody CompleteCharacterBlueprintsRequest request) {
        var value = characters.complete(projectId, versionId, parseEtag(ifMatch), request.provider(), request.instruction());
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/story-bibles/" + value.id()))
                .eTag(Long.toString(value.version())).body(value);
    }

    private static long parseEtag(String value) { return Long.parseLong(value.replace("\"", "").trim()); }
}
