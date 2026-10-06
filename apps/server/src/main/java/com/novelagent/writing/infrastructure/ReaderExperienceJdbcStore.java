package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.application.ReaderExperienceStore;
import com.novelagent.writing.application.WritingResourceNotFoundException;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ReaderExperienceEvent;
import com.novelagent.writing.domain.ReaderExperienceManuscript;
import com.novelagent.writing.domain.ReaderExperienceMemory;
import com.novelagent.writing.domain.ReaderExperiencePlan;
import com.novelagent.writing.domain.ReaderExperiencePlanInput;
import com.novelagent.writing.domain.ReaderExperienceSource;
import com.novelagent.writing.domain.ReaderExperienceState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 台账持久化。
 *
 * <p>负责计划、确认事件、正文证据和幂等记录的 SQL 映射。写入使用版本条件及调用方事务；保留正史关联和来源失效信息，不把作者接受等同于正史提交。</p>
 */
@Repository
public class ReaderExperienceJdbcStore implements ReaderExperienceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CharacterNameService names;

    public ReaderExperienceJdbcStore(JdbcTemplate jdbc, ObjectMapper mapper, CharacterNameService names) {
        this.jdbc = jdbc; this.mapper = mapper; this.names = names;
    }

    /**
     * 对人物身份和别名加共享锁，保证本次证据渲染与反向标记使用一致姓名映射。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Override public void lockNames(UUID projectId) {
        jdbc.query("SELECT id FROM story_entity WHERE project_id = ? AND entity_type = 'CHARACTER' FOR SHARE",
                (rs, n) -> rs.getObject("id", UUID.class), projectId);
        jdbc.query("SELECT id FROM entity_alias WHERE project_id = ? FOR SHARE", (rs, n) -> rs.getObject("id", UUID.class), projectId);
    }

    /**
     * 将证据中的明确人物名称转为稳定实体引用，原文证据本身仍保留以供核验。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param evidence 来源中的连续原文证据，不允许拼接或伪造引文。
     */
    @Override public String tokenizeEvidence(UUID projectId, String evidence) { return names.tokenize(projectId, evidence); }

    /**
     * 锁定项目行，保护台账事件及幂等写入；必须在调用方事务内使用。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Override public void lockProject(UUID projectId) {
        jdbc.queryForObject("SELECT id FROM novel_project WHERE id = ? FOR UPDATE", UUID.class, projectId);
    }

    /**
     * 限定项目后对来源正文加共享锁，防止证据校验与写入之间来源被并发修改。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param manuscriptId 作为来源或证据引用的正文版本 ID。
     */
    @Override public void lockManuscript(UUID projectId, UUID manuscriptId) {
        var rows = jdbc.query("SELECT id FROM manuscript_version WHERE project_id = ? AND id = ? FOR SHARE",
                (rs, n) -> rs.getObject("id", UUID.class), projectId, manuscriptId);
        if (rows.isEmpty()) throw new WritingResourceNotFoundException("来源正文", manuscriptId);
    }

    /**
     * 查询项目未软删除的计划并按存储顺序返回，不将未来计划转成正文事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Override public List<ReaderExperiencePlan> plans(UUID projectId) {
        return jdbc.query("SELECT * FROM reader_experience_plan WHERE project_id = ? AND NOT deleted ORDER BY created_at, id",
                (rs, n) -> planRow(rs), projectId);
    }

    /**
     * 读取项目内指定计划，包括判断其已删除状态所需的信息。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Override public Optional<ReaderExperiencePlan> plan(UUID projectId, UUID id) {
        return jdbc.query("SELECT * FROM reader_experience_plan WHERE project_id = ? AND id = ?",
                (rs, n) -> planRow(rs), projectId, id).stream().findFirst();
    }

    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param input 本次结构化业务输入或确认命令。
     */
    @Override public ReaderExperiencePlan create(UUID projectId, ReaderExperiencePlanInput input) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reader_experience_plan(id, project_id, kind, title, promise_text, setup_text,
                    payoff_text, aftermath_text, planned_chapter) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, input.kind().name(), input.title().trim(), input.promise(), text(input.setup()),
                text(input.payoff()), text(input.aftermath()), input.plannedChapter());
        return plan(projectId, id).orElseThrow();
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @Override public void update(UUID projectId, UUID id, ReaderExperiencePlanInput input) {
        int changed = jdbc.update("""
                UPDATE reader_experience_plan SET kind = ?, title = ?, promise_text = ?, setup_text = ?,
                    payoff_text = ?, aftermath_text = ?, planned_chapter = ?, row_version = row_version + 1, updated_at = now()
                WHERE project_id = ? AND id = ? AND row_version = ? AND NOT deleted
                """, input.kind().name(), input.title().trim(), input.promise(), text(input.setup()), text(input.payoff()),
                text(input.aftermath()), input.plannedChapter(), projectId, id, input.expectedVersion());
        checkChanged(projectId, id, input.expectedVersion(), changed);
    }

    /**
     * 按预期行版本推进台账记录版本或软删除标记，未成功更新时交由冲突检查处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param deleted 是否标记软删除，保留历史记录不物理删除正文。
     */
    @Override public void advance(UUID projectId, UUID id, long expectedVersion, boolean deleted) {
        int changed = jdbc.update("""
                UPDATE reader_experience_plan SET deleted = ?, row_version = row_version + 1, updated_at = now()
                WHERE project_id = ? AND id = ? AND row_version = ? AND NOT deleted
                """, deleted, projectId, id, expectedVersion);
        checkChanged(projectId, id, expectedVersion, changed);
    }

    /**
     * 按进展版本顺序映射不可变台账事件及来源正文、证据、提交人和提交时正史关联，不把快照字段当成当前状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param planId 读者承诺或伏笔计划 ID。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Override public List<ReaderExperienceEvent> events(UUID projectId, UUID planId) {
        return jdbc.query("SELECT * FROM reader_experience_event WHERE project_id = ? AND plan_id = ? ORDER BY entry_version",
                (rs, n) -> new ReaderExperienceEvent(rs.getObject("id", UUID.class), projectId, planId,
                        rs.getLong("entry_version"), snapshot(rs.getString("plan_snapshot")), ReaderExperienceState.valueOf(rs.getString("state")),
                        rs.getObject("manuscript_id", UUID.class), rs.getLong("manuscript_row_version"), rs.getInt("chapter_number"),
                        rs.getString("source_fingerprint"), rs.getString("evidence"), rs.getString("evidence_tokenized"),
                        rs.getString("author_note"), rs.getObject("chapter_canon_commit_id", UUID.class),
                        rs.getBoolean("canon_at_submission"), rs.getObject("submitted_by", UUID.class), rs.getString("schema_version"),
                        rs.getTimestamp("created_at").toInstant()), projectId, planId);
    }

    /**
     * 追加作者确认的不可变台账事件，保存来源正文、证据及提交时的正史关联。
     *
     * @param event 已由业务校验的台账确认事件。
     */
    @Override public void append(ReaderExperienceEvent event) {
        jdbc.update("""
                INSERT INTO reader_experience_event(id, project_id, plan_id, entry_version, plan_snapshot, state, manuscript_id,
                    manuscript_row_version, chapter_number, source_fingerprint, evidence, evidence_tokenized, author_note, chapter_canon_commit_id,
                    canon_at_submission, submitted_by, schema_version, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, event.id(), event.projectId(), event.planId(), event.entryVersion(), snapshotJson(event.planSnapshot()), event.state().name(), event.manuscriptId(),
                event.manuscriptRowVersion(), event.chapterNumber(), event.sourceFingerprint(), event.evidence(), event.evidenceTokenized(), event.authorNote(), event.chapterCanonCommitId(),
                event.canonAtSubmission(), event.submittedBy(), event.schemaVersion(), java.sql.Timestamp.from(event.createdAt()));
    }

    private static final String SOURCE_FROM = """
            FROM manuscript_version m
            LEFT JOIN canon_commit c ON c.project_id = m.project_id AND c.chapter_number = m.chapter_number AND c.active
            WHERE m.project_id = ?
            """;
    private static final String SUPERSEDED = """
            EXISTS (SELECT 1 FROM canon_commit old WHERE old.project_id = m.project_id
                AND old.manuscript_version_id = m.id AND NOT old.active)
                AND (c.manuscript_version_id IS NULL OR c.manuscript_version_id <> m.id)
            """;

    /**
     * 读取原始或已确认来源资料并保留来源版本，供下载、证据引用或后续业务复核。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param manuscriptId 作为来源或证据引用的正文版本 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Override public Optional<ReaderExperienceSource> source(UUID projectId, UUID manuscriptId) {
        return jdbc.query("SELECT m.id, m.project_id, m.row_version, m.chapter_number, m.status, m.content->>'body' AS body, "
                + "c.id AS commit_id, c.manuscript_version_id AS canon_manuscript_id, c.canon_version, (" + SUPERSEDED + ") AS superseded "
                + SOURCE_FROM + " AND m.id = ?", (rs, n) -> new ReaderExperienceSource(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getLong("row_version"), rs.getInt("chapter_number"),
                ManuscriptStatus.valueOf(rs.getString("status")), names.render(projectId, rs.getString("body")), rs.getObject("commit_id", UUID.class),
                rs.getObject("canon_manuscript_id", UUID.class), rs.getObject("canon_version", Long.class),
                rs.getBoolean("superseded")), projectId, manuscriptId).stream().findFirst();
    }

    /**
     * 读取作者接受的正文目录，标明其正史关联和被替换状态；接受正文不自动等于有效正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Override public List<ReaderExperienceManuscript> acceptedSources(UUID projectId) {
        return jdbc.query("SELECT m.id, m.row_version, m.chapter_number, m.content->>'title' AS title, "
                + "COALESCE(c.manuscript_version_id = m.id, false) AS canon, (" + SUPERSEDED + ") AS superseded "
                + SOURCE_FROM + " AND m.status = 'AUTHOR_ACCEPTED' ORDER BY m.chapter_number, m.version_number DESC",
                (rs, n) -> new ReaderExperienceManuscript(rs.getObject("id", UUID.class), rs.getLong("row_version"),
                        rs.getInt("chapter_number"), names.render(projectId, rs.getString("title")), rs.getBoolean("canon"), rs.getBoolean("superseded")), projectId);
    }

    /**
     * 按项目及请求 ID 读取既有台账变更指纹，供幂等重放核对。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param requestId 客户端请求关联或幂等 ID，具体用途见方法说明。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Override public Optional<Mutation> mutation(UUID projectId, UUID requestId) {
        return jdbc.query("SELECT plan_id, request_hash FROM reader_experience_mutation WHERE project_id = ? AND request_id = ?",
                (rs, n) -> new Mutation(rs.getObject("plan_id", UUID.class), rs.getString("request_hash")), projectId, requestId)
                .stream().findFirst();
    }

    /**
     * 保存本次台账请求的指纹、计划与提交者，防止同一请求 ID 被不同内容复用。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param requestId 客户端请求关联或幂等 ID，具体用途见方法说明。
     * @param planId 读者承诺或伏笔计划 ID。
     * @param hash 幂等请求或来源内容的稳定指纹。
     * @param actorId 执行并确认本次写入的用户 ID。
     */
    @Override public void remember(UUID projectId, UUID requestId, UUID planId, String hash, UUID actorId) {
        jdbc.update("INSERT INTO reader_experience_mutation(project_id, request_id, plan_id, request_hash, submitted_by) VALUES (?, ?, ?, ?, ?)",
                projectId, requestId, planId, hash, actorId);
    }

    /**
     * 按当前大纲分组已有有效正史摘要，保留未分配章节；不是新模型生成的全书压缩摘要。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Override public ReaderExperienceMemory memory(UUID projectId) {
        var chapters = jdbc.query("""
                SELECT m.id, m.row_version, m.schema_version, m.chapter_number, m.content->>'title' AS title,
                    m.content->>'summary' AS summary, c.id AS commit_id, c.canon_version
                FROM canon_commit c JOIN manuscript_version m ON m.id = c.manuscript_version_id AND m.project_id = c.project_id
                WHERE c.project_id = ? AND c.active AND m.status = 'AUTHOR_ACCEPTED' ORDER BY m.chapter_number
                """, (rs, n) -> new ReaderExperienceMemory.Chapter(rs.getInt("chapter_number"), rs.getObject("id", UUID.class),
                rs.getLong("row_version"), rs.getString("schema_version"), rs.getObject("commit_id", UUID.class),
                rs.getLong("canon_version"), names.render(projectId, rs.getString("title")), names.render(projectId, rs.getString("summary"))), projectId);
        var outlines = jdbc.query("""
                SELECT o.id, o.row_version, o.content::text FROM novel_project p
                JOIN outline_version o ON o.id = p.current_outline_version_id AND o.project_id = p.id
                WHERE p.id = ? AND o.status = 'PUBLISHED'
                """, (rs, n) -> new OutlineSource(rs.getObject("id", UUID.class), rs.getLong("row_version"), outline(rs.getString("content"))), projectId);
        if (outlines.isEmpty()) return new ReaderExperienceMemory("reader-experience-memory/1", "EXISTING_CANON_SUMMARIES",
                null, null, List.of(), chapters);
        var source = outlines.getFirst();
        var assigned = new HashSet<Integer>();
        var arcs = source.content().arcs().stream().map(arc -> {
            var numbers = arc.chapters().stream().map(chapter -> chapter.number()).toList();
            assigned.addAll(numbers);
            return new ReaderExperienceMemory.Arc(arc.ordinal(), names.render(projectId, arc.title()),
                    chapters.stream().filter(chapter -> numbers.contains(chapter.chapterNumber())).toList());
        }).toList();
        return new ReaderExperienceMemory("reader-experience-memory/1", "EXISTING_CANON_SUMMARIES", source.id(), source.version(),
                arcs, chapters.stream().filter(chapter -> !assigned.contains(chapter.chapterNumber())).toList());
    }

    /**
     * 解析当前发布大纲 JSON 供既有正史摘要分组，不生成新的全书摘要。
     *
     * @param json 存储中的 JSON 文本，损坏时拒绝恢复为有效对象。
     */
    private OutlineContent outline(String json) {
        try { return mapper.readValue(json, OutlineContent.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("已发布大纲无法读取", exception); }
    }

    private record OutlineSource(UUID id, long version, OutlineContent content) { }

    /**
     * 还原事件保存时的计划 JSON 快照，用于历史展示，不代替作者当前编辑的计划。
     *
     * @param json 存储中的 JSON 文本，损坏时拒绝恢复为有效对象。
     */
    private ReaderExperiencePlan snapshot(String json) {
        try { return mapper.readValue(json, ReaderExperiencePlan.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("台账计划快照无法读取", exception); }
    }

    /**
     * 序列化事件对应的计划快照，保留历史确认依据，不重算未来兑现。
     *
     * @param plan 本次读取或确认的计划，实际进展仍需原文证据。
     */
    private String snapshotJson(ReaderExperiencePlan plan) {
        try { return mapper.writeValueAsString(plan); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("台账计划快照无法保存", exception); }
    }

    /**
     * 从 SQL 行映射计划类型、文本、目标章、删除标记及并发版本；未来计划不代表已经兑现。
     *
     * @param rs 当前数据库结果行，字段对应本方法的 SQL 投影。
     */
    private ReaderExperiencePlan planRow(ResultSet rs) throws SQLException {
        return new ReaderExperiencePlan(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                ReaderExperiencePlan.Kind.valueOf(rs.getString("kind")), rs.getString("title"), rs.getString("promise_text"),
                rs.getString("setup_text"), rs.getString("payoff_text"), rs.getString("aftermath_text"),
                rs.getObject("planned_chapter", Integer.class), rs.getLong("row_version"), rs.getString("schema_version"),
                rs.getBoolean("deleted"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private void checkChanged(UUID projectId, UUID id, long expected, int changed) {
        if (changed == 1) return;
        var current = plan(projectId, id).orElseThrow(() -> new WritingResourceNotFoundException("读者体验计划", id));
        throw new ResourceVersionConflictException(expected, current.version());
    }

    private static String text(String value) { return value == null ? "" : value; }
}
