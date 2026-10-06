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

@Repository
public class ReaderExperienceJdbcStore implements ReaderExperienceStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CharacterNameService names;

    public ReaderExperienceJdbcStore(JdbcTemplate jdbc, ObjectMapper mapper, CharacterNameService names) {
        this.jdbc = jdbc; this.mapper = mapper; this.names = names;
    }

    @Override public void lockNames(UUID projectId) {
        jdbc.query("SELECT id FROM story_entity WHERE project_id = ? AND entity_type = 'CHARACTER' FOR SHARE",
                (rs, n) -> rs.getObject("id", UUID.class), projectId);
        jdbc.query("SELECT id FROM entity_alias WHERE project_id = ? FOR SHARE", (rs, n) -> rs.getObject("id", UUID.class), projectId);
    }

    @Override public String tokenizeEvidence(UUID projectId, String evidence) { return names.tokenize(projectId, evidence); }

    @Override public void lockProject(UUID projectId) {
        jdbc.queryForObject("SELECT id FROM novel_project WHERE id = ? FOR UPDATE", UUID.class, projectId);
    }

    @Override public void lockManuscript(UUID projectId, UUID manuscriptId) {
        var rows = jdbc.query("SELECT id FROM manuscript_version WHERE project_id = ? AND id = ? FOR SHARE",
                (rs, n) -> rs.getObject("id", UUID.class), projectId, manuscriptId);
        if (rows.isEmpty()) throw new WritingResourceNotFoundException("来源正文", manuscriptId);
    }

    @Override public List<ReaderExperiencePlan> plans(UUID projectId) {
        return jdbc.query("SELECT * FROM reader_experience_plan WHERE project_id = ? AND NOT deleted ORDER BY created_at, id",
                (rs, n) -> planRow(rs), projectId);
    }

    @Override public Optional<ReaderExperiencePlan> plan(UUID projectId, UUID id) {
        return jdbc.query("SELECT * FROM reader_experience_plan WHERE project_id = ? AND id = ?",
                (rs, n) -> planRow(rs), projectId, id).stream().findFirst();
    }

    @Override public ReaderExperiencePlan create(UUID projectId, ReaderExperiencePlanInput input) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reader_experience_plan(id, project_id, kind, title, promise_text, setup_text,
                    payoff_text, aftermath_text, planned_chapter) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, projectId, input.kind().name(), input.title().trim(), input.promise(), text(input.setup()),
                text(input.payoff()), text(input.aftermath()), input.plannedChapter());
        return plan(projectId, id).orElseThrow();
    }

    @Override public void update(UUID projectId, UUID id, ReaderExperiencePlanInput input) {
        int changed = jdbc.update("""
                UPDATE reader_experience_plan SET kind = ?, title = ?, promise_text = ?, setup_text = ?,
                    payoff_text = ?, aftermath_text = ?, planned_chapter = ?, row_version = row_version + 1, updated_at = now()
                WHERE project_id = ? AND id = ? AND row_version = ? AND NOT deleted
                """, input.kind().name(), input.title().trim(), input.promise(), text(input.setup()), text(input.payoff()),
                text(input.aftermath()), input.plannedChapter(), projectId, id, input.expectedVersion());
        checkChanged(projectId, id, input.expectedVersion(), changed);
    }

    @Override public void advance(UUID projectId, UUID id, long expectedVersion, boolean deleted) {
        int changed = jdbc.update("""
                UPDATE reader_experience_plan SET deleted = ?, row_version = row_version + 1, updated_at = now()
                WHERE project_id = ? AND id = ? AND row_version = ? AND NOT deleted
                """, deleted, projectId, id, expectedVersion);
        checkChanged(projectId, id, expectedVersion, changed);
    }

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

    @Override public Optional<ReaderExperienceSource> source(UUID projectId, UUID manuscriptId) {
        return jdbc.query("SELECT m.id, m.project_id, m.row_version, m.chapter_number, m.status, m.content->>'body' AS body, "
                + "c.id AS commit_id, c.manuscript_version_id AS canon_manuscript_id, c.canon_version, (" + SUPERSEDED + ") AS superseded "
                + SOURCE_FROM + " AND m.id = ?", (rs, n) -> new ReaderExperienceSource(rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getLong("row_version"), rs.getInt("chapter_number"),
                ManuscriptStatus.valueOf(rs.getString("status")), names.render(projectId, rs.getString("body")), rs.getObject("commit_id", UUID.class),
                rs.getObject("canon_manuscript_id", UUID.class), rs.getObject("canon_version", Long.class),
                rs.getBoolean("superseded")), projectId, manuscriptId).stream().findFirst();
    }

    @Override public List<ReaderExperienceManuscript> acceptedSources(UUID projectId) {
        return jdbc.query("SELECT m.id, m.row_version, m.chapter_number, m.content->>'title' AS title, "
                + "COALESCE(c.manuscript_version_id = m.id, false) AS canon, (" + SUPERSEDED + ") AS superseded "
                + SOURCE_FROM + " AND m.status = 'AUTHOR_ACCEPTED' ORDER BY m.chapter_number, m.version_number DESC",
                (rs, n) -> new ReaderExperienceManuscript(rs.getObject("id", UUID.class), rs.getLong("row_version"),
                        rs.getInt("chapter_number"), names.render(projectId, rs.getString("title")), rs.getBoolean("canon"), rs.getBoolean("superseded")), projectId);
    }

    @Override public Optional<Mutation> mutation(UUID projectId, UUID requestId) {
        return jdbc.query("SELECT plan_id, request_hash FROM reader_experience_mutation WHERE project_id = ? AND request_id = ?",
                (rs, n) -> new Mutation(rs.getObject("plan_id", UUID.class), rs.getString("request_hash")), projectId, requestId)
                .stream().findFirst();
    }

    @Override public void remember(UUID projectId, UUID requestId, UUID planId, String hash, UUID actorId) {
        jdbc.update("INSERT INTO reader_experience_mutation(project_id, request_id, plan_id, request_hash, submitted_by) VALUES (?, ?, ?, ?, ?)",
                projectId, requestId, planId, hash, actorId);
    }

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

    private OutlineContent outline(String json) {
        try { return mapper.readValue(json, OutlineContent.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("已发布大纲无法读取", exception); }
    }

    private record OutlineSource(UUID id, long version, OutlineContent content) { }

    private ReaderExperiencePlan snapshot(String json) {
        try { return mapper.readValue(json, ReaderExperiencePlan.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("台账计划快照无法读取", exception); }
    }

    private String snapshotJson(ReaderExperiencePlan plan) {
        try { return mapper.writeValueAsString(plan); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("台账计划快照无法保存", exception); }
    }

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
