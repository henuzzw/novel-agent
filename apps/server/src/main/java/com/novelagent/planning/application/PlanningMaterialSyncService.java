package com.novelagent.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.domain.CharacterBlueprint;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.ReaderExperienceSeed;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 规划资料同步。
 *
 * <p>按已确认来源确定性建立人物身份、填空档案、保存关系规划并导入明确台账。保留作者已有值及手工修改、删除记录，区分规划关系与正文事实，不调用模型。</p>
 */
@Service
public class PlanningMaterialSyncService {
    private final ProjectAccessService access;
    private final CharacterNameService names;
    private final StoryBibleVersionRepository bibles;
    private final OutlineVersionRepository outlines;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public PlanningMaterialSyncService(ProjectAccessService access, CharacterNameService names,
            StoryBibleVersionRepository bibles, OutlineVersionRepository outlines, JdbcTemplate jdbc, ObjectMapper mapper) {
        this.access = access; this.names = names; this.bibles = bibles; this.outlines = outlines;
        this.jdbc = jdbc; this.mapper = mapper;
    }

    /**
     * 同步项目当前已发布圣经及大纲，旧项目可显式调用补齐规划资料；读取页面本身不执行此操作。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional
    public void syncCurrent(UUID projectId) {
        access.requireOwnedProject(projectId);
        lock(projectId);
        var pointers = jdbc.queryForMap("SELECT current_bible_version_id, current_outline_version_id FROM novel_project WHERE id = ?", projectId);
        UUID bibleId = (UUID) pointers.get("current_bible_version_id");
        UUID outlineId = (UUID) pointers.get("current_outline_version_id");
        if (bibleId == null) throw new IllegalArgumentException("请先发布故事圣经");
        var bible = bibles.findByIdAndProjectId(bibleId, projectId).orElseThrow();
        syncBible(bible);
        if (outlineId != null) {
            syncOutline(outlines.findByIdAndProjectId(outlineId, projectId).orElseThrow());
        }
        syncCanonForeshadows(projectId);
    }

    /**
     * 从指定圣经同步人物蓝图、初始关系和明确台账；档案只填空白，不覆盖作者已有值。
     *
     * @param bible 指定圣经来源或其内容，不隐式使用其他最新草稿。
     */
    @Transactional
    public void syncBible(StoryBibleVersion bible) {
        if (bible.getStatus() != StoryBibleStatus.PUBLISHED) throw new IllegalArgumentException("只能同步已发布故事圣经");
        UUID projectId = bible.getProjectId();
        access.requireOwnedProject(projectId);
        lock(projectId);
        names.initializeFromStoryBible(projectId, bible);
        for (var blueprint : bible.getContent().characterBlueprints()) {
            UUID characterId = characterId(projectId, blueprint.name());
            jdbc.update("""
                    INSERT INTO planning_character_snapshot(project_id, source_bible_id, character_id, blueprint)
                    VALUES (?, ?, ?, CAST(? AS jsonb)) ON CONFLICT DO NOTHING
                    """, projectId, bible.getId(), characterId, json(blueprint));
            fillProfile(projectId, characterId, blueprint);
            for (int i = 0; i < blueprint.initialRelationships().size(); i++) {
                relationship(projectId, bible.getId(), characterId, characterId + ":" + i, blueprint.initialRelationships().get(i));
            }
        }
        var dynamics = bible.getContent().relationshipDynamics();
        for (int i = 0; dynamics != null && i < dynamics.size(); i++) {
            relationship(projectId, bible.getId(), null, "DYNAMIC:" + i, dynamics.get(i));
        }
        for (var seed : bible.getContent().readerExperiencePlans()) insertPlan(projectId, "BIBLE", bible.getId(), seed);
    }

    /**
     * 从指定大纲同步明确读者体验计划，保留来源版本与作者手工改动，不猜测普通物件或钩子都是伏笔。
     *
     * @param outline 来源大纲或对应的规划内容。
     */
    @Transactional
    public void syncOutline(OutlineVersion outline) {
        if (outline.getStatus() != OutlineStatus.PUBLISHED) throw new IllegalArgumentException("只能同步已发布大纲");
        access.requireOwnedProject(outline.getProjectId());
        lock(outline.getProjectId());
        for (var seed : outline.getContent().readerExperiencePlans()) {
            if (seed.plannedChapter() != null && outline.getContent().arcs().stream()
                    .flatMap(arc -> arc.chapters().stream()).noneMatch(chapter -> chapter.number() == seed.plannedChapter())) {
                throw new IllegalArgumentException("承诺或伏笔的计划兑现章不存在于大纲中：" + seed.key());
            }
            insertPlan(outline.getProjectId(), "OUTLINE", outline.getId(), seed);
        }
    }

    // Canon facts are mirrored as plans, not forged author evidence or automatic PAYOFF events.
    /**
     * 将有效正文正史伏笔接入共享台账来源，保留原正史进度，不伪造作者手工确认事件。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    public void syncCanonForeshadows(UUID projectId) {
        jdbc.update("""
                INSERT INTO reader_experience_plan(id, project_id, kind, title, promise_text, setup_text,
                    payoff_text, aftermath_text, planned_chapter, source_kind, source_id, source_key)
                SELECT f.id, f.project_id, 'FORESHADOW', left(f.title, 200),
                    COALESCE(NULLIF(f.target_effect, ''), f.title), COALESCE(f.evidence_ref, ''), '', '',
                    f.planned_resolve_chapter, 'CANON', f.id, 'FORESHADOW'
                FROM foreshadow f WHERE f.project_id = ? AND f.canon_version_to IS NULL
                ON CONFLICT DO NOTHING
                """, projectId);
    }

    /**
     * 应用作者确认的创作准备设计，建立规划人物、实体、关系与台账；不物化未经正文确认的已发生事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param taskId 创作准备等来源任务 ID。
     * @param world 已选世界观或其约束内容。
     * @param plot 剧情结构或剧情规划内容。
     */
    @Transactional
    public void syncPreparation(UUID projectId, UUID taskId, com.novelagent.planning.domain.CreationPreparation.World world,
            com.novelagent.planning.domain.CreationPreparation.Plot plot) {
        access.requireOwnedProject(projectId); lock(projectId);
        names.initializeFromBlueprints(projectId, taskId, world.characters());
        for (var blueprint : world.characters()) fillProfile(projectId, characterId(projectId, blueprint.name()), blueprint);
        for (var entity : world.entities()) {
            jdbc.update("""
                    INSERT INTO story_entity(id, project_id, entity_type, canonical_name, status, canon_version_from, evidence_ref)
                    SELECT ?, ?, ?, ?, 'PLANNED', 0, ? WHERE NOT EXISTS (
                        SELECT 1 FROM story_entity WHERE project_id = ? AND entity_type = ? AND canonical_name = ? AND canon_version_to IS NULL)
                    """, UUID.randomUUID(), projectId, entity.type(), entity.name(), "PREPARATION:" + taskId, projectId, entity.type(), entity.name());
        }
        for (var seed : plot.readerExperiencePlans()) insertPlan(projectId, "PREPARATION", taskId, seed);
    }

    /**
     * 返回查询范围内的人物关系，并保留规划与已发生事实各自的来源，不自动生成新关系。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param characterId 稳定人物实体 ID，不以显示姓名作为主键。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<PlannedRelationship> relationships(UUID projectId, UUID characterId) {
        access.requireOwnedProject(projectId);
        var result = new java.util.ArrayList<>(jdbc.query("""
                SELECT r.id, r.source_bible_id, r.character_id, r.description
                FROM planning_relationship r JOIN novel_project p ON p.id = r.project_id
                WHERE r.project_id = ? AND r.source_bible_id = p.current_bible_version_id
                    AND (CAST(? AS uuid) IS NULL OR r.character_id IS NULL OR r.character_id = ?)
                ORDER BY r.source_key
                """, (rs, n) -> new PlannedRelationship(rs.getObject("id", UUID.class),
                rs.getObject("source_bible_id", UUID.class), rs.getObject("character_id", UUID.class),
                names.render(projectId, rs.getString("description"))), projectId, characterId, characterId));
        for (var row : currentPreparation(projectId)) {
            var world = readWorld((String) row.get("world_design"));
            var plot = readPlot((String) row.get("plot_design"));
            UUID bibleId = (UUID) row.get("source_bible_id"); UUID taskId = (UUID) row.get("id");
            for (var person : world.characters()) {
                UUID id = characterId(projectId, person.name());
                if (characterId != null && !characterId.equals(id)) continue;
                for (int i = 0; i < person.initialRelationships().size(); i++) {
                    result.add(new PlannedRelationship(stableId(taskId + ":" + id + ":" + i), bibleId, id,
                            names.render(projectId, person.initialRelationships().get(i))));
                }
            }
            for (int i = 0; i < plot.relationships().size(); i++) {
                var relation = plot.relationships().get(i); UUID id = characterId(projectId, relation.source());
                UUID target = characterId(projectId, relation.target());
                if (characterId != null && !characterId.equals(id) && !characterId.equals(target)) continue;
                result.add(new PlannedRelationship(stableId(taskId + ":REL:" + i), bibleId, id,
                        names.render(projectId, "第" + relation.fromChapter() + "章规划：" + relation.source() + " → " + relation.target()
                                + "（" + relation.type() + "）" + relation.description())));
            }
        }
        return result;
    }

    /**
     * 查询当前来源保存的人物规划快照；完整蓝图与独立可编辑档案各有来源和用途。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<CharacterSnapshot> characters(UUID projectId) {
        access.requireOwnedProject(projectId);
        var result = new java.util.ArrayList<>(jdbc.query("""
                SELECT s.source_bible_id, s.character_id, s.blueprint::text FROM planning_character_snapshot s
                JOIN novel_project p ON p.id = s.project_id
                WHERE s.project_id = ? AND s.source_bible_id = p.current_bible_version_id
                ORDER BY s.character_id
                """, (rs, n) -> new CharacterSnapshot(rs.getObject("source_bible_id", UUID.class),
                rs.getObject("character_id", UUID.class), blueprint(projectId, rs.getString("blueprint"))), projectId));
        for (var row : currentPreparation(projectId)) {
            for (var person : readWorld((String) row.get("world_design")).characters()) {
                UUID id = characterId(projectId, person.name()); result.removeIf(item -> item.characterId().equals(id));
                result.add(new CharacterSnapshot((UUID) row.get("source_bible_id"), id, names.render(projectId, person, CharacterBlueprint.class)));
            }
        }
        return result;
    }

    /**
     * 查询规划资料及台账的来源版本、有效性和关联信息，供作者判断是否需要重新同步。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<PlanOrigin> origins(UUID projectId) {
        access.requireOwnedProject(projectId);
        return jdbc.query("""
                SELECT r.id, r.source_kind, r.source_id,
                    CASE r.source_kind WHEN 'BIBLE' THEN r.source_id = p.current_bible_version_id
                    WHEN 'OUTLINE' THEN r.source_id = p.current_outline_version_id AND EXISTS (
                        SELECT 1 FROM outline_version o WHERE o.id = r.source_id AND o.source_bible_version_id = p.current_bible_version_id)
                    WHEN 'CANON' THEN EXISTS (SELECT 1 FROM foreshadow f
                        WHERE f.id = r.source_id AND f.project_id = r.project_id AND f.canon_version_to IS NULL)
                    WHEN 'PREPARATION' THEN EXISTS (SELECT 1 FROM creation_preparation_current pc
                        JOIN creation_preparation_task t ON t.id = pc.task_id WHERE pc.project_id = r.project_id
                        AND t.id = r.source_id AND t.source_bible_id = p.current_bible_version_id AND t.source_outline_id = p.current_outline_version_id)
                    ELSE TRUE END AS current
                FROM reader_experience_plan r JOIN novel_project p ON p.id = r.project_id
                WHERE r.project_id = ? AND r.source_kind IS NOT NULL AND NOT r.deleted
                """, (rs, n) -> new PlanOrigin(rs.getObject("id", UUID.class), rs.getString("source_kind"),
                rs.getObject("source_id", UUID.class), rs.getBoolean("current")), projectId);
    }

    /**
     * 按来源及稳定键幂等导入明确台账，保留作者编辑和软删除；来源替换只标有效性，不伪造进展事件。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param kind 规划条目或业务对象的分类。
     * @param sourceId 本次处理的基准记录 ID，不隐式改用最新版本。
     * @param seed 创建规划条目的明确来源设计，不能推定为已发生事实。
     */
    private void insertPlan(UUID projectId, String kind, UUID sourceId, ReaderExperienceSeed seed) {
        jdbc.update("""
                INSERT INTO reader_experience_plan(id, project_id, kind, title, promise_text, setup_text,
                    payoff_text, aftermath_text, planned_chapter, source_kind, source_id, source_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), projectId, seed.kind(), seed.title(), seed.promise(), seed.setup(),
                seed.payoff(), seed.aftermath(), seed.plannedChapter(), kind, sourceId, seed.key());
    }

    private void relationship(UUID projectId, UUID bibleId, UUID characterId, String key, String text) {
        if (text == null || text.isBlank()) return;
        jdbc.update("""
                INSERT INTO planning_relationship(id, project_id, source_bible_id, source_key, character_id, description)
                VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING
                """, UUID.randomUUID(), projectId, bibleId, key, characterId, text);
    }

    private UUID characterId(UUID projectId, String name) {
        var ids = jdbc.query("""
                SELECT e.id FROM story_entity e WHERE e.project_id = ? AND e.entity_type = 'CHARACTER'
                AND e.canon_version_to IS NULL AND (e.canonical_name = ? OR e.source_name = ?
                OR EXISTS (SELECT 1 FROM entity_alias a WHERE a.entity_id = e.id AND a.alias = ? AND a.canon_version_to IS NULL))
                """, (rs, n) -> rs.getObject("id", UUID.class), projectId, name, name, name);
        if (ids.size() != 1) throw new IllegalArgumentException("人物身份无法唯一匹配：" + name);
        return ids.getFirst();
    }

    /**
     * 仅填人物档案中的空白设定，保留作者已有值；完整蓝图另存来源快照，不以发布新版本覆盖手工档案。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param characterId 稳定人物实体 ID，不以显示姓名作为主键。
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     */
    private void fillProfile(UUID projectId, UUID characterId, CharacterBlueprint value) {
        jdbc.update("INSERT INTO character_profile(character_id, project_id) VALUES (?, ?) ON CONFLICT DO NOTHING", characterId, projectId);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("gender", value.gender()); fields.put("age_description", value.ageDescription());
        fields.put("identity_text", value.identity()); fields.put("appearance", value.appearance());
        fields.put("background", value.background()); fields.put("external_personality", value.externalPersonality());
        fields.put("internal_personality", value.internalPersonality()); fields.put("core_desire", value.coreDesire());
        fields.put("fear", value.fear()); fields.put("flaw", value.flaw()); fields.put("values_text", value.values());
        fields.put("speech_style", value.speechStyle()); fields.put("behavior_habits", value.behaviorHabits());
        fields.put("secret_text", value.secret()); fields.put("character_arc", value.characterArc());
        fields.put("behavior_boundaries", value.behaviorBoundaries());
        // Column names only come from the fixed map above; author text is always bound as parameters.
        for (var field : fields.entrySet()) {
            if (field.getValue().isBlank()) continue;
            jdbc.update("UPDATE character_profile SET " + field.getKey() + " = ?, row_version = row_version + 1, updated_at = now()"
                    + " WHERE project_id = ? AND character_id = ? AND NULLIF(btrim(" + field.getKey() + "), '') IS NULL",
                    field.getValue(), projectId, characterId);
        }
    }

    private void lock(UUID projectId) {
        jdbc.queryForObject("SELECT id FROM novel_project WHERE id = ? FOR UPDATE", UUID.class, projectId);
    }
    private List<Map<String, Object>> currentPreparation(UUID projectId) {
        return jdbc.queryForList("""
                SELECT t.id, t.source_bible_id, t.world_design::text, t.plot_design::text FROM creation_preparation_current c
                JOIN creation_preparation_task t ON t.id = c.task_id JOIN novel_project p ON p.id = c.project_id
                WHERE c.project_id = ? AND t.source_bible_id = p.current_bible_version_id AND t.source_outline_id = p.current_outline_version_id
                """, projectId);
    }
    private com.novelagent.planning.domain.CreationPreparation.World readWorld(String value) {
        try { return mapper.readValue(value, com.novelagent.planning.domain.CreationPreparation.World.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("创作准备人物读取失败", e); }
    }
    private com.novelagent.planning.domain.CreationPreparation.Plot readPlot(String value) {
        try { return mapper.readValue(value, com.novelagent.planning.domain.CreationPreparation.Plot.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("创作准备剧情读取失败", e); }
    }
    private UUID stableId(String value) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("规划资料无法保存", e); }
    }
    private CharacterBlueprint blueprint(UUID projectId, String value) {
        try { return names.render(projectId, mapper.readValue(value, CharacterBlueprint.class), CharacterBlueprint.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("人物规划无法读取", e); }
    }

    public record PlannedRelationship(UUID id, UUID sourceBibleId, UUID characterId, String description) { }
    public record CharacterSnapshot(UUID sourceBibleId, UUID characterId, CharacterBlueprint blueprint) { }
    public record PlanOrigin(UUID planId, String sourceKind, UUID sourceId, boolean current) { }
}
