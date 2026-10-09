package com.novelagent.agent.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.writing.domain.FactProposal;
import java.util.ArrayList;
import java.util.List;
import java.util.Comparator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class RecentChapterSummariesTool implements NovelReadTool {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    RecentChapterSummariesTool(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public NovelToolName name() {
        return NovelToolName.GET_RECENT_CHAPTER_SUMMARIES;
    }

    @Override
    public NovelToolResult execute(NovelToolRequest request) {
        List<NovelMemoryContext.SemanticMemory> memories = new ArrayList<>();
        List<NovelMemoryContext.GraphFact> facts = new ArrayList<>();
        List<PlanSource> plans = jdbc.query("""
                SELECT o.id::text AS outline_id, o.row_version AS outline_row_version,
                       b.id::text AS bible_id, b.row_version AS bible_row_version, o.content::text AS outline_content
                  FROM novel_project p
                  JOIN outline_version o ON o.id = p.current_outline_version_id AND o.project_id = p.id
                  JOIN story_bible_version b ON b.id = o.source_bible_version_id AND b.project_id = p.id
                 WHERE p.id = ? AND o.status = 'PUBLISHED' AND b.status = 'PUBLISHED'
                   AND b.id = p.current_bible_version_id
                """, (row, index) -> new PlanSource("来源项目=" + request.projectId()
                        + "；大纲=" + row.getString("outline_id") + "；大纲行版本=" + row.getLong("outline_row_version")
                        + "；圣经=" + row.getString("bible_id") + "；圣经行版本=" + row.getLong("bible_row_version")
                        + "；大纲内容指纹=" + NovelMemoryContext.fingerprint(row.getString("outline_content")),
                        readOutline(row.getString("outline_content"))), request.projectId());
        if (plans.isEmpty()) throw new IllegalArgumentException("项目当前已发布大纲或关联圣经不可用");
        PlanSource source = plans.getFirst();
        List<ChapterPlan> ordered = source.content().arcs().stream().flatMap(arc -> arc.chapters().stream())
                .sorted(Comparator.comparingInt(ChapterPlan::number)).toList();
        int current = -1;
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).number() == request.chapterNumber()) { current = i; break; }
        }
        if (current < 0) throw new IllegalArgumentException("当前大纲中不存在目标章节");
        for (int i = current - 1; i >= Math.max(0, current - 2); i--) {
            int chapter = ordered.get(i).number();
            List<RecentChapter> chapters = jdbc.query("""
                    SELECT chapter_number, canon_version, summary, body, contract_content, accepted_facts,
                           manuscript_id, manuscript_row_version, version_number, contract_id,
                           contract_row_version, contract_version_number, source_outline_id, commit_id,
                           source_outline_row_version, source_bible_id, source_bible_row_version
                      FROM (
                        SELECT m.chapter_number, c.canon_version, m.version_number,
                               m.content->>'summary' AS summary, m.content->>'body' AS body,
                               COALESCE(m.writing_basis->'plan', ct.content)::text AS contract_content, c.accepted_facts::text AS accepted_facts,
                               m.id::text AS manuscript_id, m.row_version AS manuscript_row_version,
                               ct.id::text AS contract_id, ct.row_version AS contract_row_version,
                               ct.version_number AS contract_version_number, source_o.id::text AS source_outline_id,
                               c.id::text AS commit_id,
                               source_o.row_version AS source_outline_row_version,
                               source_b.id::text AS source_bible_id, source_b.row_version AS source_bible_row_version,
                               0 AS priority
                          FROM canon_commit c
                          JOIN manuscript_version m ON m.id = c.manuscript_version_id AND m.project_id = c.project_id
                               AND m.chapter_number = c.chapter_number
                          LEFT JOIN chapter_contract_version ct ON ct.id = m.source_contract_version_id AND ct.project_id = m.project_id
                               AND ct.chapter_number = m.chapter_number
                          JOIN outline_version source_o ON source_o.id = COALESCE((m.writing_basis->>'outlineId')::uuid, ct.source_outline_version_id) AND source_o.project_id = m.project_id
                          JOIN story_bible_version source_b ON source_b.id = source_o.source_bible_version_id AND source_b.project_id = source_o.project_id
                         WHERE c.project_id = ? AND c.chapter_number = ? AND c.active = TRUE
                           AND c.canon_version <= ?
                        UNION ALL
                        SELECT m.chapter_number, 0 AS canon_version, m.version_number,
                               m.content->>'summary' AS summary, m.content->>'body' AS body,
                               COALESCE(m.writing_basis->'plan', ct.content)::text AS contract_content, NULL::text AS accepted_facts,
                               m.id::text AS manuscript_id, m.row_version AS manuscript_row_version,
                               ct.id::text AS contract_id, ct.row_version AS contract_row_version,
                               ct.version_number AS contract_version_number, source_o.id::text AS source_outline_id,
                               NULL::text AS commit_id,
                               source_o.row_version AS source_outline_row_version,
                               source_b.id::text AS source_bible_id, source_b.row_version AS source_bible_row_version,
                               1 AS priority
                          FROM manuscript_version m
                          LEFT JOIN chapter_contract_version ct ON ct.id = m.source_contract_version_id AND ct.project_id = m.project_id
                               AND ct.chapter_number = m.chapter_number
                          JOIN outline_version source_o ON source_o.id = COALESCE((m.writing_basis->>'outlineId')::uuid, ct.source_outline_version_id) AND source_o.project_id = m.project_id
                          JOIN story_bible_version source_b ON source_b.id = source_o.source_bible_version_id AND source_b.project_id = source_o.project_id
                         WHERE m.project_id = ? AND m.chapter_number = ? AND m.status = 'AUTHOR_ACCEPTED'
                      ) recent
                     ORDER BY priority, version_number DESC
                     LIMIT 1
                    """, (result, row) -> new RecentChapter(new NovelMemoryContext.SemanticMemory(
                            result.getInt("chapter_number"), result.getLong("canon_version"), 1.0,
                            provenance(result, request) + "\n" + result.getString("summary"),
                            "来源写作计划（仅为计划）：" + result.getString("contract_content")
                                    + (result.getString("body") == null || result.getString("body").isBlank()
                                            ? "" : "\n正文：" + result.getString("body")),
                            true, result.getString("contract_content"), result.getString("body")),
                            result.getString("accepted_facts")),
                    request.projectId(), chapter, request.canonVersion(), request.projectId(), chapter);
            if (chapters.isEmpty()) {
                memories.add(new NovelMemoryContext.SemanticMemory(chapter, -2, 1.0,
                        "第" + chapter + "章前文缺失：无有效正史正文或作者确认正文。", "", true));
            }
            for (RecentChapter recent : chapters) {
                memories.add(recent.memory());
                if (recent.memory().canonVersion() > 0 && recent.acceptedFacts() != null) {
                    facts.addAll(facts(recent.memory().canonVersion(), recent.acceptedFacts()));
                }
            }
        }
        ChapterPlan next = current + 1 < ordered.size() ? ordered.get(current + 1) : null;
        memories.add(new NovelMemoryContext.SemanticMemory(next == null ? request.chapterNumber() : next.number(),
                NovelMemoryContext.FUTURE_PLAN, 1.0, source.provenance() + "\n本章第" + request.chapterNumber()
                + "章；" + (current == 0 ? "无前章" : "前章第" + ordered.get(current - 1).number() + "章")
                + "；" + (next == null ? "末章，无下一章计划。" : "下一章第" + next.number() + "章，仅供未来边界约束。"),
                next == null ? "" : "未来计划（不能提前写成事实或认知）：" + json(next)));
        return new NovelToolResult(List.copyOf(memories), List.copyOf(facts));
    }

    private String provenance(java.sql.ResultSet row, NovelToolRequest request) throws java.sql.SQLException {
        return "来源项目=" + request.projectId() + "；提交=" + row.getString("commit_id")
                + "；正文=" + row.getString("manuscript_id") + "；正文版本=" + row.getInt("version_number")
                + "；正文行版本=" + row.getLong("manuscript_row_version")
                + "；来源大纲=" + row.getString("source_outline_id") + "；内容指纹="
                + NovelMemoryContext.fingerprint(row.getString("summary") + "\n" + row.getString("body")
                        + "\n" + row.getString("contract_content") + "\n" + row.getString("accepted_facts"))
                + "；来源大纲行版本=" + row.getLong("source_outline_row_version")
                + "；来源圣经=" + row.getString("source_bible_id") + "；来源圣经行版本=" + row.getLong("source_bible_row_version");
    }

    private OutlineContent readOutline(String json) {
        try { return mapper.readValue(json, OutlineContent.class); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("已发布大纲无法解析", exception); }
    }

    private String json(ChapterPlan plan) {
        try { return mapper.writeValueAsString(plan); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("下一章计划无法解析", exception); }
    }

    private record PlanSource(String provenance, OutlineContent content) { }

    private List<NovelMemoryContext.GraphFact> facts(long canonVersion, String json) {
        try {
            List<NovelMemoryContext.GraphFact> result = new ArrayList<>();
            for (FactProposal fact : mapper.readValue(json, FactProposal[].class)) {
                result.add(new NovelMemoryContext.GraphFact(canonVersion, fact.subject(), fact.predicate(),
                        fact.object(), fact.evidence()));
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("前章正史事实无法解析", exception);
        }
    }

    private record RecentChapter(NovelMemoryContext.SemanticMemory memory, String acceptedFacts) {}
}
