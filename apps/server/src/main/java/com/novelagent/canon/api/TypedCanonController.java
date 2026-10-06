package com.novelagent.canon.api;

import com.novelagent.canon.application.TypedCanonQueryService;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 结构化正史。
 *
 * <p>提供实体、状态、事件、关系、知识、别名与伏笔的查询及显式别名维护。规划资料与正文已发生事实来源不同，不在这里从圣经猜测事实。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canon")
public class TypedCanonController {
    private final TypedCanonQueryService service;

    public TypedCanonController(TypedCanonQueryService service) {
        this.service = service;
    }

    /**
     * 查询当前有效实体，可按类型筛选；未来规划实体与正文正史的来源标记不能混用。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param type 本次查询类别或反序列化目标类型，具体含义由签名区分。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/entities")
    public List<StoryEntityResponse> entities(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String type) {
        return service.entities(projectId, type);
    }

    /**
     * 查询正史事件时间线；未知故事时间保留未知，不从章节号推造日期。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/timeline")
    public List<StoryEventResponse> timeline(@PathVariable UUID projectId) {
        return service.timeline(projectId);
    }

    /**
     * 读取指定实体当前有效状态，沿用正史有效范围，不把作者侧未来设定当成已经发生的状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/entities/{entityId}/state")
    public List<EntityStateResponse> entityState(
            @PathVariable UUID projectId,
            @PathVariable UUID entityId) {
        return service.entityState(projectId, entityId);
    }

    /**
     * 读取已有正史伏笔记录，与未来计划台账分别表达来源和进度。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/foreshadows")
    public List<ForeshadowResponse> foreshadows(@PathVariable UUID projectId) {
        return service.foreshadows(projectId);
    }

    /**
     * 返回查询范围内的人物关系，并保留规划与已发生事实各自的来源，不自动生成新关系。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/relationships")
    public List<StoryRelationshipResponse> relationships(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID entityId) {
        return service.relationships(projectId, entityId);
    }

    /**
     * 查询角色已建立的知识记录；作者档案中的秘密不代表角色已知。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param characterId 稳定人物实体 ID，不以显示姓名作为主键。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/knowledge")
    public List<CharacterKnowledgeResponse> knowledge(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID characterId) {
        return service.knowledge(projectId, characterId);
    }

    /**
     * 列出指定实体已有别名，供稳定身份消歧使用，不据相似名字合并实体。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/entities/{entityId}/aliases")
    public List<EntityAliasResponse> aliases(@PathVariable UUID projectId, @PathVariable UUID entityId) {
        return service.aliases(projectId, entityId);
    }

    /**
     * 按作者明确输入维护实体别名，限定项目及实体归属；这是显式写入，不是模型推断。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/entities/{entityId}/aliases")
    public EntityAliasResponse addAlias(@PathVariable UUID projectId, @PathVariable UUID entityId,
            @Valid @RequestBody CreateEntityAliasRequest request) {
        return service.addAlias(projectId, entityId, request.alias(), request.aliasType());
    }

    /**
     * 读取已记录的名称出现及其关联实体，保留来源用于排查消歧。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/entities/{entityId}/mentions")
    public List<EntityMentionResponse> mentions(@PathVariable UUID projectId, @PathVariable UUID entityId) {
        return service.mentions(projectId, entityId);
    }
}
