package com.novelagent.planning.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.domain.CreationPreparation;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.writing.application.ReaderExperienceService;
import com.novelagent.writing.domain.ReaderExperienceState;
import com.novelagent.writing.domain.ReaderExperienceSubmission;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreationPreparationContextService {
    private final ProjectAccessService access;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final CharacterNameService names;
    private final ReaderExperienceService ledger;
    public CreationPreparationContextService(ProjectAccessService access, JdbcTemplate jdbc, ObjectMapper mapper,
            CharacterNameService names, ReaderExperienceService ledger) {
        this.access = access; this.jdbc = jdbc; this.mapper = mapper; this.names = names; this.ledger = ledger;
    }
    @Transactional(readOnly = true)
    public String context(UUID projectId, UUID outlineId, int chapter) {
        access.requireOwnedProject(projectId);
        var rows = jdbc.queryForList("""
                SELECT t.id, t.world_design::text, t.plot_design::text FROM creation_preparation_current c
                JOIN creation_preparation_task t ON t.id = c.task_id JOIN novel_project p ON p.id = c.project_id
                WHERE c.project_id = ? AND t.source_bible_id = p.current_bible_version_id AND t.source_outline_id = ?
                    AND ? BETWEEN t.start_chapter AND t.end_chapter AND t.status = 'CONFIRMED'
                """, projectId, outlineId, chapter);
        if (rows.isEmpty()) return "";
        try {
            var row = rows.getFirst(); var world = mapper.readValue((String) row.get("world_design"), CreationPreparation.World.class);
            var plot = mapper.readValue((String) row.get("plot_design"), CreationPreparation.Plot.class);
            var result = mapper.createObjectNode(); result.put("taskId", row.get("id").toString());
            result.put("boundary", "以下为作者确认的创作设定和未来规划，不是已发生事实。正文正史优先；计划到达某章不表示角色已经获知秘密，须在正文中发生并经确认。");
            var units = plot.units().stream().filter(unit -> chapter >= unit.startChapter() && chapter <= unit.endChapter()).toList();
            result.set("units", mapper.valueToTree(units));
            var relevant = units.stream().flatMap(unit -> unit.characters().stream()).collect(java.util.stream.Collectors.toSet());
            result.set("characters", mapper.valueToTree(world.characters().stream().filter(person -> relevant.contains(person.name())).toList()));
            result.set("entities", mapper.valueToTree(world.entities()));
            result.set("plannedRelationships", mapper.valueToTree(plot.relationships().stream().filter(item -> item.fromChapter() == chapter).toList()));
            result.set("plannedKnowledge", mapper.valueToTree(plot.knowledge().stream().filter(item -> item.knownFromChapter() == chapter).toList()));
            result.set("timeline", mapper.valueToTree(plot.timeline().stream().filter(item -> item.chapter() == chapter).toList()));
            var planKeys = units.stream().flatMap(unit -> unit.planKeys().stream()).collect(java.util.stream.Collectors.toSet());
            var plans = jdbc.queryForList("""
                    SELECT id, source_key, kind, title, promise_text, setup_text, payoff_text, aftermath_text,
                        planned_chapter, row_version FROM reader_experience_plan
                    WHERE project_id = ? AND source_kind = 'PREPARATION' AND source_id = ? AND NOT deleted
                    ORDER BY source_key
                    """, projectId, row.get("id")).stream().filter(plan -> planKeys.contains(plan.get("source_key"))
                            || Integer.valueOf(chapter).equals(plan.get("planned_chapter"))).toList();
            result.set("readerExperiencePlans", mapper.valueToTree(plans));
            return names.render(projectId, result.toString());
        } catch (JsonProcessingException e) { throw new IllegalStateException("创作准备上下文读取失败", e); }
    }
    public record Link(UUID id, UUID planId, String title, int chapterNumber, String state, String evidence, boolean stale, boolean recorded) { }
    public record Checkpoint(String key, String title, int startChapter, int endChapter, boolean ready, boolean reviewed, boolean stale) { }
    @Transactional(readOnly = true)
    public List<Checkpoint> checkpoints(UUID projectId) {
        access.requireOwnedProject(projectId);
        var rows = jdbc.queryForList("""
                SELECT t.plot_design::text, t.source_bible_id, t.source_outline_id,
                    p.current_bible_version_id, p.current_outline_version_id, p.current_canon_version
                FROM creation_preparation_current c JOIN creation_preparation_task t ON t.id = c.task_id
                JOIN novel_project p ON p.id = c.project_id WHERE c.project_id = ?
                """, projectId);
        if (rows.isEmpty()) return List.of();
        var row = rows.getFirst();
        boolean stale = !row.get("source_bible_id").equals(row.get("current_bible_version_id"))
                || !row.get("source_outline_id").equals(row.get("current_outline_version_id"));
        var committed = new java.util.HashSet<>(jdbc.queryForList("SELECT chapter_number FROM canon_commit WHERE project_id = ? AND active", Integer.class, projectId));
        var reviewed = jdbc.queryForList("""
                SELECT start_chapter, end_chapter FROM creation_preparation_task WHERE project_id = ? AND mode = 'REVIEW' AND status = 'CONFIRMED'
                    AND source_bible_id = ? AND source_outline_id = ? AND (source_snapshot->>'canonVersion')::bigint = ?
                """, projectId, row.get("current_bible_version_id"), row.get("current_outline_version_id"), row.get("current_canon_version"));
        try {
            var plot = mapper.readValue((String) row.get("plot_design"), CreationPreparation.Plot.class);
            return plot.units().stream().map(unit -> new Checkpoint(unit.key(), names.render(projectId, unit.title()), unit.startChapter(), unit.endChapter(),
                    java.util.stream.IntStream.rangeClosed(unit.startChapter(), unit.endChapter()).allMatch(committed::contains),
                    reviewed.stream().anyMatch(review -> (Integer) review.get("start_chapter") <= unit.startChapter()
                            && (Integer) review.get("end_chapter") >= unit.endChapter()), stale)).toList();
        } catch (JsonProcessingException e) { throw new IllegalStateException("剧情复核节点无法读取", e); }
    }
    @Transactional(readOnly = true)
    public List<Link> links(UUID projectId) {
        access.requireOwnedProject(projectId);
        return jdbc.query("""
                SELECT l.*, p.title, c.chapter_number,
                    (NOT c.active OR f.canon_version_to IS NOT NULL OR p.deleted OR (p.row_version <> l.plan_row_version AND NOT progress.recorded)) AS stale,
                    progress.recorded
                FROM chapter_plan_link l JOIN reader_experience_plan p ON p.id = l.plan_id
                JOIN canon_commit c ON c.id = l.source_commit_id JOIN story_fact f ON f.id = l.source_fact_id
                CROSS JOIN LATERAL (SELECT EXISTS(SELECT 1 FROM reader_experience_event e
                    WHERE e.plan_id = l.plan_id AND e.manuscript_id = c.manuscript_version_id AND e.state = l.proposed_state
                    AND e.evidence_tokenized = l.evidence_tokenized AND e.entry_version = l.plan_row_version + 1
                    AND p.row_version = e.entry_version AND c.active AND f.canon_version_to IS NULL AND NOT p.deleted) AS recorded) progress
                WHERE l.project_id = ? ORDER BY c.chapter_number, l.created_at
                """, (rs, n) -> new Link(rs.getObject("id", UUID.class), rs.getObject("plan_id", UUID.class),
                names.render(projectId, rs.getString("title")), rs.getInt("chapter_number"), rs.getString("proposed_state"),
                names.render(projectId, rs.getString("evidence")), rs.getBoolean("stale"), rs.getBoolean("recorded")), projectId);
    }
    @Transactional
    public void confirmLink(UUID projectId, UUID id, UUID requestId, long planVersion, boolean authorConfirmed) {
        access.requireOwnedProject(projectId);
        if (!authorConfirmed || requestId == null) throw new IllegalArgumentException("台账进度必须明确确认");
        jdbc.queryForObject("SELECT id FROM novel_project WHERE id = ? FOR UPDATE", UUID.class, projectId);
        var link = links(projectId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("台账关联不存在"));
        if (link.recorded()) return;
        if (link.stale()) throw new IllegalArgumentException("台账或正史来源已失效");
        UUID manuscriptId = jdbc.queryForObject("SELECT c.manuscript_version_id FROM chapter_plan_link l JOIN canon_commit c ON c.id = l.source_commit_id WHERE l.id = ? AND l.project_id = ?", UUID.class, id, projectId);
        var source = ledger.source(projectId, manuscriptId);
        ledger.submit(projectId, link.planId(), new ReaderExperienceSubmission(requestId, planVersion,
                ReaderExperienceState.valueOf(link.state()), source.id(), source.rowVersion(), source.fingerprint(), link.evidence(),
                "从已确认复核报告关联正史事实", true));
    }
}
