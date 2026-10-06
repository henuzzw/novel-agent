package com.novelagent.canon.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.api.EntityStateResponse;
import com.novelagent.canon.api.CharacterKnowledgeResponse;
import com.novelagent.canon.api.EntityAliasResponse;
import com.novelagent.canon.api.EntityMentionResponse;
import com.novelagent.canon.api.ForeshadowResponse;
import com.novelagent.canon.api.StoryEntityResponse;
import com.novelagent.canon.api.StoryEventResponse;
import com.novelagent.canon.api.StoryRelationshipResponse;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.project.domain.NovelProject;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结构化正史。
 *
 * <p>从 PostgreSQL 查询当前有效的实体、状态、事件、关系、知识和伏笔。按正史有效范围区分失效记录；别名维护是显式写入，不触发模型。</p>
 */
@Service
public class TypedCanonQueryService {
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actor;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public TypedCanonQueryService(NovelProjectRepository projects, CurrentActorProvider actor,
            JdbcTemplate jdbc, ObjectMapper mapper) {
        this.projects = projects;
        this.actor = actor;
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /**
     * 查询当前有效实体，可按类型筛选；未来规划实体与正文正史的来源标记不能混用。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param type 本次查询类别或反序列化目标类型，具体含义由签名区分。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<StoryEntityResponse> entities(UUID projectId, String type) {
        requireOwnedProject(projectId);
        if (type == null || type.isBlank()) {
            return jdbc.query("""
                    SELECT id, entity_type, canonical_name, status, canon_version_from
                    FROM story_entity
                    WHERE project_id = ? AND canon_version_to IS NULL
                    ORDER BY entity_type, canonical_name
                    """, (rs, row) -> new StoryEntityResponse(rs.getObject("id", UUID.class),
                    rs.getString("entity_type"), rs.getString("canonical_name"),
                    rs.getString("status"), rs.getLong("canon_version_from")), projectId);
        }
        return jdbc.query("""
                SELECT id, entity_type, canonical_name, status, canon_version_from
                FROM story_entity
                WHERE project_id = ? AND entity_type = ? AND canon_version_to IS NULL
                ORDER BY canonical_name
                """, (rs, row) -> new StoryEntityResponse(rs.getObject("id", UUID.class),
                rs.getString("entity_type"), rs.getString("canonical_name"),
                rs.getString("status"), rs.getLong("canon_version_from")), projectId,
                type.trim().toUpperCase(Locale.ROOT));
    }

    /**
     * 查询正史事件时间线；未知故事时间保留未知，不从章节号推造日期。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<StoryEventResponse> timeline(UUID projectId) {
        requireOwnedProject(projectId);
        return jdbc.query("""
                SELECT id, title, summary, story_time_text, narrative_chapter, importance,
                       canon_version_from, evidence_ref
                FROM story_event
                WHERE project_id = ? AND canon_version_to IS NULL
                ORDER BY narrative_chapter, canon_version_from, created_at
                """, (rs, row) -> new StoryEventResponse(rs.getObject("id", UUID.class),
                rs.getString("title"), rs.getString("summary"), rs.getString("story_time_text"),
                rs.getInt("narrative_chapter"), rs.getString("importance"),
                rs.getLong("canon_version_from"), rs.getString("evidence_ref")), projectId);
    }

    /**
     * 读取指定实体当前有效状态，沿用正史有效范围，不把作者侧未来设定当成已经发生的状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<EntityStateResponse> entityState(UUID projectId, UUID entityId) {
        requireOwnedProject(projectId);
        return jdbc.query("""
                SELECT DISTINCT ON (field_key)
                       id, field_key, after_value, narrative_chapter, canon_version_from, evidence_ref
                FROM entity_state_change
                WHERE project_id = ? AND entity_id = ? AND canon_version_to IS NULL
                ORDER BY field_key, narrative_chapter DESC, canon_version_from DESC, created_at DESC
                """, (rs, row) -> new EntityStateResponse(rs.getObject("id", UUID.class),
                rs.getString("field_key"), json(rs.getString("after_value")),
                rs.getInt("narrative_chapter"), rs.getLong("canon_version_from"),
                rs.getString("evidence_ref")), projectId, entityId);
    }

    /**
     * 读取已有正史伏笔记录，与未来计划台账分别表达来源和进度。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<ForeshadowResponse> foreshadows(UUID projectId) {
        requireOwnedProject(projectId);
        return jdbc.query("""
                SELECT id, title, target_effect, current_status, planned_resolve_chapter,
                       canon_version_from, evidence_ref
                FROM foreshadow
                WHERE project_id = ? AND canon_version_to IS NULL
                ORDER BY planned_resolve_chapter NULLS LAST, created_at
                """, (rs, row) -> new ForeshadowResponse(rs.getObject("id", UUID.class),
                rs.getString("title"), rs.getString("target_effect"), rs.getString("current_status"),
                rs.getObject("planned_resolve_chapter", Integer.class),
                rs.getLong("canon_version_from"), rs.getString("evidence_ref")), projectId);
    }

    /**
     * 返回查询范围内的人物关系，并保留规划与已发生事实各自的来源，不自动生成新关系。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<StoryRelationshipResponse> relationships(UUID projectId, UUID entityId) {
        requireOwnedProject(projectId);
        String sql = """
                SELECT r.id, r.source_entity_id, source.canonical_name AS source_name,
                       r.target_entity_id, target.canonical_name AS target_name,
                       r.relation_type, r.attributes, commit.chapter_number,
                       r.canon_version_from, r.evidence_ref
                FROM story_relationship r
                JOIN story_entity source ON source.id = r.source_entity_id
                JOIN story_entity target ON target.id = r.target_entity_id
                JOIN canon_commit commit ON commit.id = r.source_commit_id
                WHERE r.project_id = ? AND r.canon_version_to IS NULL
                """;
        Object[] arguments;
        if (entityId == null) {
            sql += " ORDER BY commit.chapter_number DESC, r.canon_version_from DESC, source.canonical_name";
            arguments = new Object[] {projectId};
        } else {
            sql += " AND (r.source_entity_id = ? OR r.target_entity_id = ?)"
                    + " ORDER BY commit.chapter_number DESC, r.canon_version_from DESC, source.canonical_name";
            arguments = new Object[] {projectId, entityId, entityId};
        }
        return jdbc.query(sql, (rs, row) -> new StoryRelationshipResponse(
                rs.getObject("id", UUID.class),
                rs.getObject("source_entity_id", UUID.class), rs.getString("source_name"),
                rs.getObject("target_entity_id", UUID.class), rs.getString("target_name"),
                rs.getString("relation_type"), json(rs.getString("attributes")),
                rs.getInt("chapter_number"), rs.getLong("canon_version_from"),
                rs.getString("evidence_ref")), arguments);
    }

    /**
     * 查询角色已建立的知识记录；作者档案中的秘密不代表角色已知。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param characterId 稳定人物实体 ID，不以显示姓名作为主键。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<CharacterKnowledgeResponse> knowledge(UUID projectId, UUID characterId) {
        requireOwnedProject(projectId);
        String sql = """
                SELECT knowledge.id, knowledge.character_id, entity.canonical_name AS character_name,
                       knowledge.fact_id, fact.subject_text, fact.predicate, fact.object_text,
                       knowledge.knowledge_type, knowledge.belief_truth, knowledge.confidence,
                       knowledge.narrative_chapter, knowledge.canon_version_from, knowledge.evidence_ref
                FROM character_knowledge knowledge
                JOIN story_entity entity ON entity.id = knowledge.character_id
                JOIN story_fact fact ON fact.id = knowledge.fact_id
                WHERE knowledge.project_id = ? AND knowledge.canon_version_to IS NULL
                """;
        Object[] arguments;
        if (characterId == null) {
            sql += " ORDER BY knowledge.narrative_chapter DESC, knowledge.canon_version_from DESC";
            arguments = new Object[] {projectId};
        } else {
            sql += " AND knowledge.character_id = ?"
                    + " ORDER BY knowledge.narrative_chapter DESC, knowledge.canon_version_from DESC";
            arguments = new Object[] {projectId, characterId};
        }
        return jdbc.query(sql, (rs, row) -> {
            var confidence = rs.getBigDecimal("confidence");
            return new CharacterKnowledgeResponse(
                    rs.getObject("id", UUID.class),
                    rs.getObject("character_id", UUID.class), rs.getString("character_name"),
                    rs.getObject("fact_id", UUID.class), rs.getString("subject_text"),
                    rs.getString("predicate"), rs.getString("object_text"),
                    rs.getString("knowledge_type"), rs.getString("belief_truth"),
                    confidence == null ? null : confidence.doubleValue(),
                    rs.getInt("narrative_chapter"), rs.getLong("canon_version_from"),
                    rs.getString("evidence_ref"));
        }, arguments);
    }

    /**
     * 按作者明确输入维护实体别名，限定项目及实体归属；这是显式写入，不是模型推断。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @param alias 作者明确补充的实体别名。
     * @param aliasType 别名类别，用于展示及实体消歧。
     */
    @Transactional
    public EntityAliasResponse addAlias(UUID projectId, UUID entityId, String alias, String aliasType) {
        NovelProject project = requireOwnedProject(projectId);
        String value = alias == null ? "" : alias.trim();
        if (value.isBlank()) throw new IllegalArgumentException("实体别名不能为空");
        if (List.of("他", "她", "它", "他们", "她们", "它们", "自己", "对方").contains(value)) {
            throw new IllegalArgumentException("代词不能登记为全局实体别名");
        }
        Long entityCount = jdbc.queryForObject(
                "SELECT count(*) FROM story_entity WHERE id = ? AND project_id = ? AND canon_version_to IS NULL",
                Long.class, entityId, projectId);
        if (entityCount == null || entityCount == 0) throw new IllegalArgumentException("实体不存在");
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO entity_alias
                    (id, project_id, entity_id, alias, alias_type, canon_version_from)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (entity_id, alias) DO NOTHING
                """, id, projectId, entityId, value, aliasType, project.getCurrentCanonVersion());
        return jdbc.queryForObject("""
                SELECT id, entity_id, alias, alias_type, canon_version_from
                FROM entity_alias WHERE entity_id = ? AND alias = ? AND canon_version_to IS NULL
                """, (rs, row) -> new EntityAliasResponse(rs.getObject("id", UUID.class),
                rs.getObject("entity_id", UUID.class), rs.getString("alias"),
                rs.getString("alias_type"), rs.getLong("canon_version_from")), entityId, value);
    }

    /**
     * 列出指定实体已有别名，供稳定身份消歧使用，不据相似名字合并实体。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<EntityAliasResponse> aliases(UUID projectId, UUID entityId) {
        requireOwnedProject(projectId);
        return jdbc.query("""
                SELECT id, entity_id, alias, alias_type, canon_version_from
                FROM entity_alias
                WHERE project_id = ? AND entity_id = ? AND canon_version_to IS NULL
                ORDER BY alias
                """, (rs, row) -> new EntityAliasResponse(rs.getObject("id", UUID.class),
                rs.getObject("entity_id", UUID.class), rs.getString("alias"),
                rs.getString("alias_type"), rs.getLong("canon_version_from")), projectId, entityId);
    }

    /**
     * 读取已记录的名称出现及其关联实体，保留来源用于排查消歧。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param entityId 当前项目实体 ID，类型与有效性由业务流程核对。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<EntityMentionResponse> mentions(UUID projectId, UUID entityId) {
        requireOwnedProject(projectId);
        return jdbc.query("""
                SELECT id, proposal_id, mention_role, mention_text, resolved_entity_id,
                       resolution_method, confidence, evidence_ref
                FROM entity_mention
                WHERE project_id = ? AND resolved_entity_id = ?
                ORDER BY created_at DESC
                """, (rs, row) -> new EntityMentionResponse(rs.getObject("id", UUID.class),
                rs.getString("proposal_id"), rs.getString("mention_role"),
                rs.getString("mention_text"), rs.getObject("resolved_entity_id", UUID.class),
                rs.getString("resolution_method"), rs.getDouble("confidence"),
                rs.getString("evidence_ref")), projectId, entityId);
    }

    private NovelProject requireOwnedProject(UUID projectId) {
        return projects.findById(projectId)
                .filter(project -> project.getOwnerId().equals(actor.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private JsonNode json(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("正史状态值损坏", exception);
        }
    }
}
