package com.novelagent.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.planning.domain.CreationPreparation;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.application.WritingResourceNotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 创作准备存储。
 *
 * <p>管理来源快照、范围、阶段认领、设计校验、编辑失效和完成状态。短事务隔离模型等待；取消或来源变化后拒绝迟到结果，准备完成不等于已应用。</p>
 */
@Service
public class CreationPreparationStore {
    private final ProjectAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CharacterNameService names;
    public CreationPreparationStore(ProjectAccessService access, JdbcTemplate jdbc, ObjectMapper mapper, CharacterNameService names) {
        this.access = access; this.jdbc = jdbc; this.mapper = mapper; this.names = names;
    }
    public record Create(UUID requestId, String mode, ModelProvider provider, Integer startChapter, Integer endChapter, String instruction) { }
    public record Task(UUID id, UUID projectId, String mode, ModelProvider provider, String instruction,
            UUID sourceBibleId, UUID sourceOutlineId, String sourceHash, JsonNode sourceSnapshot, int startChapter,
            int endChapter, String status, int nextStep, CreationPreparation.World worldDesign,
            CreationPreparation.Plot plotDesign, CreationPreparation.Review reviewReport, UUID resultOutlineId,
            String errorMessage, long version, Instant updatedAt) { }
    public record View(Task task, boolean stale, List<String> ruleWarnings) { }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<View> list(UUID projectId) {
        access.requireOwnedProject(projectId);
        var cache = new java.util.HashMap<String, ObjectNode>();
        return jdbc.query("SELECT * FROM creation_preparation_task WHERE project_id = ? ORDER BY created_at DESC LIMIT 30",
                (rs, n) -> task(rs), projectId).stream().map(task -> view(task, cache)).toList();
    }
    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View get(UUID projectId, UUID id) { access.requireOwnedProject(projectId); return view(require(projectId, id)); }
    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param input 本次结构化业务输入或确认命令。
     */
    @Transactional
    public View create(UUID projectId, Create input) {
        ownAndLock(projectId);
        if (input == null || input.requestId() == null || !Set.of("PREPARE", "REVIEW").contains(input.mode() == null ? "" : input.mode())
                || input.provider() == null || input.provider() == ModelProvider.LOCAL_TEMPLATE
                || input.instruction() != null && input.instruction().length() > 4000) throw new IllegalArgumentException("请选择真实模型并填写有效创作准备请求");
        String requestHash = hash(mapper.valueToTree(input));
        var replay = jdbc.query("SELECT * FROM creation_preparation_task WHERE project_id = ? AND request_id = ?", (rs, n) -> task(rs), projectId, input.requestId());
        if (!replay.isEmpty()) {
            String prior = jdbc.queryForObject("SELECT request_hash FROM creation_preparation_task WHERE id = ?", String.class, replay.getFirst().id());
            if (!requestHash.equals(prior)) throw new IllegalArgumentException("请求标识已用于不同内容");
            return view(replay.getFirst());
        }
        ObjectNode all = snapshot(projectId, 1, 0);
        Set<Integer> chapters = chapters(all);
        int start = input.startChapter() == null ? ("PREPARE".equals(input.mode()) ? all.path("lastCanonChapter").asInt() + 1 : chapters.iterator().next()) : input.startChapter();
        int end = input.endChapter() == null ? chapters.stream().mapToInt(Integer::intValue).max().orElseThrow() : input.endChapter();
        if (end < start) throw new IllegalArgumentException("没有待规划章节，请选择复核或调整章节范围");
        for (int chapter = start; chapter <= end; chapter++) {
            if (!chapters.contains(chapter)) throw new IllegalArgumentException("章节范围必须存在且连续");
            if (chapter == Integer.MAX_VALUE) break;
        }
        if ("PREPARE".equals(input.mode()) && start <= all.path("lastCanonChapter").asInt()) throw new IllegalArgumentException("创作准备只能细化尚未发生的章节，已有正史请使用复核");
        ObjectNode source = snapshot(projectId, start, end);
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO creation_preparation_task(id, project_id, request_id, request_hash, mode, provider, instruction,
                    source_bible_id, source_outline_id, source_hash, source_snapshot, start_chapter, end_chapter, status, next_step)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, 'READY', ?)
                """, id, projectId, input.requestId(), requestHash, input.mode(), input.provider().name(), value(input.instruction()),
                UUID.fromString(source.path("bibleId").asText()), UUID.fromString(source.path("outlineId").asText()), hash(source), source.toString(),
                start, end, "PREPARE".equals(input.mode()) ? 0 : 2);
        return view(require(projectId, id));
    }
    /**
     * 在短事务内认领本次执行或修订尝试，校验当前状态、来源和版本；认领不是模型成功。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     */
    @Transactional
    public Task claim(UUID projectId, UUID id, long version) {
        ownAndLock(projectId); Task task = require(projectId, id); version(task, version); fresh(task);
        if (!task.status().equals("READY")) throw new IllegalArgumentException("任务当前不能执行，请先恢复或确认状态");
        if (jdbc.queryForObject("SELECT count(*) FROM creation_preparation_task WHERE project_id = ? AND status = 'RUNNING'", Integer.class, projectId) != 0) {
            throw new IllegalArgumentException("项目已有运行中的创作准备，请等待或取消该任务");
        }
        jdbc.update("UPDATE creation_preparation_task SET status = 'RUNNING', error_message = NULL, row_version = row_version + 1, updated_at = now() WHERE id = ?", id);
        return require(projectId, id);
    }
    /**
     * 保存当前认领尝试的结果并推进阶段，复核来源或尝试未变化；迟到输出不能覆盖新尝试。
     *
     * @param claim 已认领的任务及尝试快照，不允许其他尝试的结果覆盖。
     * @param output 模型输出或已经得到的结构化结果，保存前必须校验。
     */
    @Transactional
    public View finish(Task claim, JsonNode output) {
        ownAndLock(claim.projectId()); Task current = require(claim.projectId(), claim.id()); version(current, claim.version());
        if (!current.status().equals("RUNNING")) throw new IllegalArgumentException("任务已取消或结束，拒绝迟到结果");
        fresh(current);
        String column = switch (current.nextStep()) { case 0 -> "world_design"; case 1 -> "plot_design"; case 2 -> "review_report"; default -> throw new IllegalArgumentException("任务阶段无效"); };
        validate(current, output);
        int next = current.nextStep() + 1;
        jdbc.update("UPDATE creation_preparation_task SET " + column + " = CAST(? AS jsonb), next_step = ?, status = ?, row_version = row_version + 1, updated_at = now() WHERE id = ?",
                output.toString(), next, next == 3 ? "AWAITING_CONFIRMATION" : "READY", current.id());
        return view(require(current.projectId(), current.id()));
    }
    /**
     * 记录当前尝试失败，保留可恢复来源及状态；取消或过期尝试不应被旧结果重新激活。
     *
     * @param claim 已认领的任务及尝试快照，不允许其他尝试的结果覆盖。
     * @param failure 本次失败原因或来源冲突信息。
     */
    @Transactional
    public void fail(Task claim, RuntimeException failure) {
        var detail = com.novelagent.agent.application.ModelFailureDetails.from(failure);
        jdbc.update("""
                UPDATE creation_preparation_task SET status = ?, error_message = ?, row_version = row_version + 1, updated_at = now()
                WHERE id = ? AND project_id = ? AND row_version = ? AND status = 'RUNNING'
                """, failure instanceof com.novelagent.agent.application.GenerationStoppedException ? "CANCELLED" : "FAILED",
                detail.type() + ": " + detail.detail(), claim.id(), claim.projectId(), claim.version());
    }
    /**
     * 按显式动作执行任务取消或恢复，并校验来源、版本与允许的状态转换。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     * @param action 本次请求执行的业务动作。
     */
    @Transactional
    public View action(UUID projectId, UUID id, long version, String action) {
        ownAndLock(projectId); Task task = require(projectId, id); version(task, version);
        String status;
        if (action.equals("cancel")) {
            if (Set.of("CONFIRMED", "CANCELLED").contains(task.status())) throw new IllegalArgumentException("任务已结束");
            status = "CANCELLED";
        } else {
            fresh(task);
            boolean abandoned = task.status().equals("RUNNING") && task.updatedAt().isBefore(Instant.now().minusSeconds(1500));
            if (!task.status().equals("FAILED") && !abandoned) throw new IllegalArgumentException("只有失败任务或超时25分钟的运行任务可以明确恢复");
            status = "READY";
        }
        jdbc.update("UPDATE creation_preparation_task SET status = ?, error_message = NULL, row_version = row_version + 1, updated_at = now() WHERE id = ?", status, id);
        return view(require(projectId, id));
    }
    /**
     * 保存作者编辑并遵循源版本及授权范围；上游资料改变后，依赖它的旧检查不能继续当作当前依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     * @param world 已选世界观或其约束内容。
     * @param plot 剧情结构或剧情规划内容。
     */
    @Transactional
    public View edit(UUID projectId, UUID id, long version, CreationPreparation.World world, CreationPreparation.Plot plot) {
        ownAndLock(projectId); Task task = require(projectId, id); version(task, version); fresh(task);
        if (!task.mode().equals("PREPARE") || !Set.of("READY", "FAILED", "AWAITING_CONFIRMATION").contains(task.status())) throw new IllegalArgumentException("当前任务不能编辑规划");
        validateWorld(task, world); validatePlot(task, world, plot);
        jdbc.update("""
                UPDATE creation_preparation_task SET world_design = CAST(? AS jsonb), plot_design = CAST(? AS jsonb),
                    review_report = NULL, status = 'READY', next_step = 2, error_message = NULL, row_version = row_version + 1, updated_at = now() WHERE id = ?
                """, json(world), json(plot), id);
        return view(require(projectId, id));
    }
    /**
     * 读取本项目要求存在的记录，不允许跨项目来源进入当前业务。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    public Task require(UUID projectId, UUID id) {
        return jdbc.query("SELECT * FROM creation_preparation_task WHERE project_id = ? AND id = ?", (rs, n) -> task(rs), projectId, id)
                .stream().findFirst().orElseThrow(() -> new WritingResourceNotFoundException("创作准备任务", id));
    }
    /**
     * 先验证归属再获取项目写锁，保护本次确认或状态更新，不把长模型等待置于锁内。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    public void ownAndLock(UUID projectId) {
        access.requireOwnedProject(projectId); jdbc.queryForObject("SELECT id FROM novel_project WHERE id = ? FOR UPDATE", UUID.class, projectId);
    }
    /**
     * 核对任务创建时来源快照仍适用，失效时拒绝继续保存或确认。
     *
     * @param task 当前执行的业务任务及其状态。
     */
    public void fresh(Task task) {
        if (!task.sourceHash().equals(hash(snapshot(task.projectId(), task.startChapter(), task.endChapter())))) throw new IllegalArgumentException("故事圣经、大纲、人物资料、台账或正史已变化，请重新创建任务；旧结果不会覆盖新资料");
    }
    /**
     * 读取指定版本并限定所属项目；版本 ID 与用于并发编辑的行版本是不同概念。
     *
     * @param task 当前执行的业务任务及其状态。
     * @param version 本次操作要求匹配的业务行版本。
     */
    public static void version(Task task, long version) { if (task.version() != version) throw new ResourceVersionConflictException(version, task.version()); }
    /**
     * 构造本阶段模型的完整结构化输入，分别保留既有正史、规划设计、范围和来源快照，供预算及来源指纹计算。
     *
     * @param task 当前执行的业务任务及其状态。
     */
    public ObjectNode modelInput(Task task) {
        var input = mapper.createObjectNode(); input.put("mode", task.mode()); input.put("authorInstruction", task.instruction());
        input.put("start_chapter", task.startChapter()); input.put("end_chapter", task.endChapter());
        input.set("source_snapshot", task.sourceSnapshot());
        input.set("world_design", mapper.valueToTree(task.worldDesign())); input.set("plot_design", mapper.valueToTree(task.plotDesign()));
        return input;
    }
    /**
     * 从源大纲提取本任务的连续章节范围，未知或越界范围由创建校验拒绝。
     *
     * @param snapshot 本次读取的来源快照，供保存时复核一致性。
     */
    public Set<Integer> chapters(JsonNode snapshot) {
        Set<Integer> result = new TreeSet<>();
        for (var arc : snapshot.path("outline").path("arcs")) for (var chapter : arc.path("chapters")) result.add(chapter.path("number").asInt());
        if (result.isEmpty()) throw new IllegalArgumentException("已发布大纲没有章节");
        return result;
    }
    private void validate(Task task, JsonNode output) {
        switch (task.nextStep()) {
            case 0 -> validateWorld(task, read(output, CreationPreparation.World.class));
            case 1 -> validatePlot(task, task.worldDesign(), read(output, CreationPreparation.Plot.class));
            case 2 -> {
                var review = read(output, CreationPreparation.Review.class);
                review.validate(modelInput(task), chapters(task.sourceSnapshot()), task.sourceSnapshot().path("lastCanonChapter").asInt());
                if (task.mode().equals("PREPARE") && (!review.adjustments().isEmpty() || !review.planLinks().isEmpty())) throw new IllegalArgumentException("创作准备复核不能修改大纲或关联历史事实");
                for (var link : review.planLinks()) {
                    boolean plan = false, fact = false;
                    for (var node : task.sourceSnapshot().path("plans")) if (node.path("id").asText().equals(link.planId()) && !node.path("deleted").asBoolean()) plan = true;
                    for (var node : task.sourceSnapshot().path("facts")) if (node.path("id").asText().equals(link.factId())
                            && node.path("fact_type").asText().startsWith("FORESHADOW") && node.path("evidence_ref").asText().contains(link.evidence())) fact = true;
                    if (!plan || !fact) throw new IllegalArgumentException("台账关联必须引用有效计划和有证据的正史伏笔事实");
                }
            }
            default -> throw new IllegalArgumentException("创作准备阶段无效");
        }
    }
    private void validatePlot(Task task, CreationPreparation.World world, CreationPreparation.Plot plot) {
        plot.validate(world, chapters(task.sourceSnapshot()), task.startChapter(), task.endChapter());
        for (var proposed : plot.readerExperiencePlans()) {
            for (var existing : task.sourceSnapshot().path("plans")) {
                if (!existing.path("deleted").asBoolean() && existing.path("title").asText().equals(proposed.title())) {
                    throw new IllegalArgumentException("该台账计划已经存在，请复用原计划而非重复新增：" + proposed.title());
                }
            }
        }
    }
    private void validateWorld(Task task, CreationPreparation.World world) {
        if (world == null || world.characters().isEmpty()) throw new IllegalArgumentException("需要有效人物规划");
        var columns = java.util.Map.ofEntries(
                java.util.Map.entry("identity", "identity_text"), java.util.Map.entry("appearance", "appearance"),
                java.util.Map.entry("background", "background"), java.util.Map.entry("externalPersonality", "external_personality"),
                java.util.Map.entry("internalPersonality", "internal_personality"), java.util.Map.entry("coreDesire", "core_desire"),
                java.util.Map.entry("fear", "fear"), java.util.Map.entry("flaw", "flaw"), java.util.Map.entry("values", "values_text"),
                java.util.Map.entry("speechStyle", "speech_style"), java.util.Map.entry("behaviorHabits", "behavior_habits"),
                java.util.Map.entry("secret", "secret_text"), java.util.Map.entry("characterArc", "character_arc"),
                java.util.Map.entry("behaviorBoundaries", "behavior_boundaries"));
        var profiles = new java.util.HashMap<String, JsonNode>();
        for (var name : task.sourceSnapshot().path("names")) {
            for (var profile : task.sourceSnapshot().path("profiles")) if (name.path("id").asText().equals(profile.path("character_id").asText())) {
                profiles.put(name.path("canonicalName").asText(), profile);
            }
        }
        // Independent author profiles take precedence; completion never silently replaces stable filled fields.
        for (var existing : task.sourceSnapshot().path("bible").path("characterBlueprints")) {
            var proposed = world.characters().stream().filter(item -> item.name().equals(existing.path("name").asText())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("不能删除已有圣经人物"));
            JsonNode tree = mapper.valueToTree(proposed);
            ObjectNode baseline = existing.deepCopy();
            JsonNode profile = profiles.get(proposed.name());
            if (profile != null) columns.forEach((field, column) -> {
                if (!profile.path(column).asText().isBlank()) baseline.put(field, profile.path(column).asText());
            });
            baseline.properties().forEach(field -> {
                if (field.getValue().isTextual() && !field.getValue().asText().isBlank() || field.getValue().isArray() && !field.getValue().isEmpty()) {
                    if (!field.getValue().equals(tree.path(field.getKey()))) throw new IllegalArgumentException("不能覆盖圣经人物已有字段：" + proposed.name() + " / " + field.getKey());
                }
            });
        }
        for (var person : world.characters()) {
            JsonNode profile = profiles.get(person.name()); if (profile == null) continue;
            JsonNode proposed = mapper.valueToTree(person);
            columns.forEach((field, column) -> {
                if (!profile.path(column).asText().isBlank() && !profile.path(column).asText().equals(proposed.path(field).asText())) {
                    throw new IllegalArgumentException("不能覆盖作者人物档案已有字段：" + person.name() + " / " + field);
                }
            });
        }
    }
    /**
     * 将准备任务、当前来源有效性及关联内容组合为展示视图；完成与已应用状态分别呈现。
     *
     * @param task 当前执行的业务任务及其状态。
     */
    private View view(Task task) { return view(task, new java.util.HashMap<>()); }
    /**
     * 将准备任务、当前来源有效性及关联内容组合为展示视图；完成与已应用状态分别呈现。
     *
     * @param task 当前执行的业务任务及其状态。
     * @param cache 本次操作范围内的已解析结果缓存，不代替持久化来源。
     */
    private View view(Task task, java.util.Map<String, ObjectNode> cache) {
        boolean stale;
        try {
            ObjectNode source = cache.computeIfAbsent(task.startChapter() + ":" + task.endChapter(), ignored -> snapshot(task.projectId(), task.startChapter(), task.endChapter()));
            stale = task.status().equals("CONFIRMED") ? !source.path("bibleId").asText().equals(task.sourceBibleId().toString())
                    || !source.path("outlineId").asText().equals(task.sourceOutlineId().toString()) : !task.sourceHash().equals(hash(source));
        } catch (IllegalArgumentException e) { stale = true; }
        List<String> warnings = jdbc.query("""
                SELECT p.title FROM reader_experience_plan p WHERE p.project_id = ? AND NOT p.deleted AND p.planned_chapter BETWEEN ? AND ?
                  AND p.planned_chapter <= (SELECT COALESCE(max(chapter_number), 0) FROM canon_commit WHERE project_id = ? AND active)
                  AND NOT EXISTS (SELECT 1 FROM reader_experience_event e WHERE e.plan_id = p.id AND e.state = 'PAYOFF')
                ORDER BY p.planned_chapter, p.id
                """, (rs, n) -> "计划节点已到，需核对兑现证据：" + rs.getString(1), task.projectId(), task.startChapter(), task.endChapter(), task.projectId());
        return new View(task, stale, warnings);
    }
    private ObjectNode snapshot(UUID projectId, int start, int end) {
        var pointers = jdbc.queryForMap("SELECT current_bible_version_id, current_outline_version_id, current_canon_version, settings::text FROM novel_project WHERE id = ?", projectId);
        UUID bibleId = (UUID) pointers.get("current_bible_version_id"), outlineId = (UUID) pointers.get("current_outline_version_id");
        if (bibleId == null || outlineId == null) throw new IllegalArgumentException("请先发布故事圣经与大纲");
        var bible = jdbc.queryForMap("SELECT content::text, row_version, status FROM story_bible_version WHERE id = ? AND project_id = ?", bibleId, projectId);
        var outline = jdbc.queryForMap("SELECT content::text, row_version, status, source_bible_version_id FROM outline_version WHERE id = ? AND project_id = ?", outlineId, projectId);
        if (!"PUBLISHED".equals(bible.get("status")) || !"PUBLISHED".equals(outline.get("status")) || !bibleId.equals(outline.get("source_bible_version_id"))) throw new IllegalArgumentException("已发布大纲必须关联当前故事圣经");
        var result = mapper.createObjectNode(); result.put("bibleId", bibleId.toString()); result.put("outlineId", outlineId.toString());
        result.set("bibleVersion", mapper.valueToTree(bible.get("row_version"))); result.set("outlineVersion", mapper.valueToTree(outline.get("row_version")));
        result.set("canonVersion", mapper.valueToTree(pointers.get("current_canon_version")));
        result.set("projectSettings", parse((String) pointers.get("settings")));
        var intent = jdbc.query("SELECT row_to_json(i)::text FROM creative_intent i WHERE project_id = ?", (rs, n) -> parse(rs.getString(1)), projectId);
        result.set("creativeIntent", intent.isEmpty() ? mapper.nullNode() : intent.getFirst());
        result.set("bible", names.render(projectId, parse((String) bible.get("content")), JsonNode.class));
        result.set("outline", names.render(projectId, parse((String) outline.get("content")), JsonNode.class));
        result.set("names", mapper.valueToTree(names.list(projectId)));
        result.set("profiles", mapper.valueToTree(jdbc.queryForList("SELECT * FROM character_profile WHERE project_id = ? ORDER BY character_id", projectId)));
        result.set("plans", mapper.valueToTree(jdbc.queryForList("SELECT id, kind, title, promise_text, setup_text, payoff_text, planned_chapter, row_version, deleted FROM reader_experience_plan WHERE project_id = ? ORDER BY id", projectId)));
        result.set("ledgerEvents", mapper.valueToTree(jdbc.queryForList("SELECT id, plan_id, entry_version, state, manuscript_id, manuscript_row_version, source_fingerprint FROM reader_experience_event WHERE project_id = ? ORDER BY id", projectId)));
        result.set("canon", mapper.valueToTree(jdbc.queryForList("SELECT c.id, c.chapter_number, c.canon_version, m.content->>'summary' AS summary FROM canon_commit c JOIN manuscript_version m ON m.id = c.manuscript_version_id WHERE c.project_id = ? AND c.active AND c.chapter_number BETWEEN ? AND ? ORDER BY c.chapter_number", projectId, start, end)));
        result.set("previousCanon", mapper.valueToTree(jdbc.queryForList("SELECT c.id, c.chapter_number, m.content->>'summary' AS summary FROM canon_commit c JOIN manuscript_version m ON m.id = c.manuscript_version_id WHERE c.project_id = ? AND c.active AND c.chapter_number < ? ORDER BY c.chapter_number DESC LIMIT 2", projectId, start)));
        result.set("currentState", mapper.valueToTree(jdbc.queryForList("""
                SELECT DISTINCT ON (s.entity_id, s.field_key) s.entity_id, e.canonical_name, s.field_key,
                    s.after_value::text AS value, s.narrative_chapter, s.evidence_ref FROM entity_state_change s
                JOIN story_entity e ON e.id = s.entity_id WHERE s.project_id = ? AND s.canon_version_to IS NULL
                ORDER BY s.entity_id, s.field_key, s.canon_version_from DESC, s.narrative_chapter DESC, s.id
                """, projectId)));
        result.set("currentRelationships", mapper.valueToTree(jdbc.queryForList("""
                SELECT r.id, a.canonical_name AS source_name, b.canonical_name AS target_name, r.relation_type,
                    r.evidence_ref, c.chapter_number FROM story_relationship r JOIN story_entity a ON a.id = r.source_entity_id
                JOIN story_entity b ON b.id = r.target_entity_id JOIN canon_commit c ON c.id = r.source_commit_id
                WHERE r.project_id = ? AND r.canon_version_to IS NULL AND c.active ORDER BY r.id
                """, projectId)));
        result.set("currentKnowledge", mapper.valueToTree(jdbc.queryForList("""
                SELECT k.id, e.canonical_name, k.knowledge_type, k.belief_truth, k.narrative_chapter,
                    f.object_text AS information, k.evidence_ref FROM character_knowledge k JOIN story_entity e ON e.id = k.character_id
                JOIN story_fact f ON f.id = k.fact_id WHERE k.project_id = ? AND k.canon_version_to IS NULL AND f.canon_version_to IS NULL ORDER BY k.id
                """, projectId)));
        result.set("facts", mapper.valueToTree(jdbc.queryForList("SELECT f.id, f.fact_type, f.subject_text, f.predicate, f.object_text, f.evidence_ref, c.chapter_number, c.id AS commit_id FROM story_fact f JOIN canon_commit c ON c.id = f.source_commit_id WHERE f.project_id = ? AND f.canon_version_to IS NULL AND c.active AND c.chapter_number BETWEEN ? AND ? ORDER BY c.chapter_number, f.id", projectId, start, end)));
        result.put("lastCanonChapter", jdbc.queryForObject("SELECT COALESCE(max(chapter_number), 0) FROM canon_commit WHERE project_id = ? AND active", Integer.class, projectId));
        var prior = jdbc.queryForList("SELECT t.id, t.world_design::text, t.plot_design::text FROM creation_preparation_current p JOIN creation_preparation_task t ON t.id = p.task_id WHERE p.project_id = ? AND t.source_bible_id = ? AND t.source_outline_id = ?", projectId, bibleId, outlineId);
        if (!prior.isEmpty()) {
            var accepted = result.putObject("acceptedPreparation"); accepted.put("id", prior.getFirst().get("id").toString());
            accepted.set("world", parse((String) prior.getFirst().get("world_design"))); accepted.set("plot", parse((String) prior.getFirst().get("plot_design")));
        }
        return result;
    }
    /**
     * 将 SQL 行和 JSON 设计、报告映射为准备任务，保留来源版本与尝试，缺失阶段输出保持 null。
     *
     * @param rs 当前数据库结果行，字段对应本方法的 SQL 投影。
     */
    private Task task(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Task(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getString("mode"), ModelProvider.valueOf(rs.getString("provider")), rs.getString("instruction"),
                rs.getObject("source_bible_id", UUID.class), rs.getObject("source_outline_id", UUID.class), rs.getString("source_hash"), parse(rs.getString("source_snapshot")),
                rs.getInt("start_chapter"), rs.getInt("end_chapter"), rs.getString("status"), rs.getInt("next_step"),
                readNullable(rs.getString("world_design"), CreationPreparation.World.class), readNullable(rs.getString("plot_design"), CreationPreparation.Plot.class),
                readNullable(rs.getString("review_report"), CreationPreparation.Review.class), rs.getObject("result_outline_id", UUID.class), rs.getString("error_message"), rs.getLong("row_version"), rs.getTimestamp("updated_at").toInstant());
    }
    /**
     * 将准备任务来源或设计序列化为 JSON，序列化失败拒绝保存，避免持久化不完整依据。
     *
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     */
    public String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("规划格式不合法", e); } }
    /**
     * 把存储 JSON 解析为请求的领域类型，解析失败按准备任务错误处理，不制造空设计。
     *
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     */
    public JsonNode parse(String value) { try { return mapper.readTree(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("规划 JSON 不合法", e); } }
    private <T> T readNullable(String value, Class<T> type) { return value == null ? null : read(parse(value), type); }
    private <T> T read(JsonNode value, Class<T> type) { try { return mapper.treeToValue(value, type); } catch (JsonProcessingException e) { throw new IllegalArgumentException("规划结构不合法", e); } }
    private String hash(JsonNode node) { return NovelMemoryContext.fingerprint(node.toString()); }
    private static String value(String value) { return value == null ? "" : value.trim(); }
}
