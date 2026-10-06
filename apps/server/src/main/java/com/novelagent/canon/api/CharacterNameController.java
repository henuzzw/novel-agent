package com.novelagent.canon.api;

import com.novelagent.canon.application.CharacterNameService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 人物命名。
 *
 * <p>查询规划或正史人物、从圣经识别姓名并更新显示名称。命名不是正文事实抽取，修改显示名通过稳定人物 ID 关联已有资料。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/characters")
public class CharacterNameController {
    private final CharacterNameService service;

    public CharacterNameController(CharacterNameService service) { this.service = service; }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<CharacterNameResponse> list(@PathVariable UUID projectId) { return service.list(projectId); }

    /**
     * 从项目最新保存的圣经建立规划人物命名记录；已有身份与作者改名保留。此入口不生成圣经、不自动填写完整档案或提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @PostMapping("/actions/initialize-from-bible")
    public List<CharacterNameResponse> initialize(@PathVariable UUID projectId) {
        return service.initializeFromStoryBible(projectId);
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param characterId 稳定人物实体 ID，不以显示姓名作为主键。
     * @param match If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PutMapping("/{characterId}")
    public ResponseEntity<CharacterNameResponse> update(@PathVariable UUID projectId,
            @PathVariable UUID characterId, @RequestHeader("If-Match") String match,
            @Valid @RequestBody UpdateCharacterNameRequest request) {
        CharacterNameResponse value = service.update(projectId, characterId,
                Long.parseLong(match.replace("\"", "").trim()), request.canonicalName(),
                request.nickname(), request.title());
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }
}
