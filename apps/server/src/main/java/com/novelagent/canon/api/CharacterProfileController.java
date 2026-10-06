package com.novelagent.canon.api;

import com.novelagent.canon.application.CharacterProfileService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人物档案。
 *
 * <p>查询及保存独立人物设定。档案中的秘密与未来弧光是作者侧资料，不自动成为人物已知信息或正文正史。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/character-profiles")
public class CharacterProfileController {
    private final CharacterProfileService service;

    public CharacterProfileController(CharacterProfileService service) {
        this.service = service;
    }

    /**
     * 为当前有效人物幂等建立缺失的空档案后返回列表；这是带事务的补空操作，不会调用模型补齐详细设定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<CharacterProfileResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId);
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
    public ResponseEntity<CharacterProfileResponse> update(@PathVariable UUID projectId,
            @PathVariable UUID characterId, @RequestHeader("If-Match") String match,
            @Valid @RequestBody UpdateCharacterProfileRequest request) {
        CharacterProfileResponse value = service.update(projectId, characterId,
                Long.parseLong(match.replace("\"", "").trim()), request);
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }
}
