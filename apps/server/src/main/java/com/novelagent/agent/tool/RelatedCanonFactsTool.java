package com.novelagent.agent.tool;

import com.novelagent.memory.application.NovelMemoryContext;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class RelatedCanonFactsTool implements NovelReadTool {
    private final JdbcTemplate jdbc;

    RelatedCanonFactsTool(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public NovelToolName name() {
        return NovelToolName.GET_RELATED_CANON_FACTS;
    }

    @Override
    public boolean optional() {
        return false;
    }

    @Override
    public NovelToolResult execute(NovelToolRequest request) {
        List<NovelMemoryContext.GraphFact> facts = query(request, true, request.graphFactCandidateLimit());
        if (facts.isEmpty()) {
            facts = query(request, false, Math.min(4, request.graphFactCandidateLimit()));
        }
        return NovelToolResult.facts(facts);
    }

    private List<NovelMemoryContext.GraphFact> query(NovelToolRequest request, boolean relevantOnly, int limit) {
        String relevance = relevantOnly ? "AND (strpos(?, COALESCE(e.canonical_name,f.subject_text))>0 OR strpos(?, f.object_text)>0)" : "";
        String sql = """
                SELECT c.canon_version, COALESCE(e.canonical_name,f.subject_text), f.predicate, f.object_text, f.evidence_ref
                FROM story_fact f JOIN canon_commit c ON c.id=f.source_commit_id AND c.project_id=f.project_id
                LEFT JOIN story_entity e ON e.id=f.subject_entity_id AND e.project_id=f.project_id AND e.canon_version_to IS NULL
                WHERE f.project_id=? AND c.active AND c.chapter_number<? AND c.canon_version<=?
                  AND f.canon_version_from<=? AND (f.canon_version_to IS NULL OR f.canon_version_to>?)
                  %s
                ORDER BY c.chapter_number DESC, f.canon_version_from DESC, f.id LIMIT ?
                """.formatted(relevance);
        Object[] args = relevantOnly
                ? new Object[] { request.projectId(), request.chapterNumber(), request.canonVersion(), request.canonVersion(), request.canonVersion(), request.query(), request.query(), limit }
                : new Object[] { request.projectId(), request.chapterNumber(), request.canonVersion(), request.canonVersion(), request.canonVersion(), limit };
        return jdbc.query(sql, (rs, row) -> new NovelMemoryContext.GraphFact(rs.getLong(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5)), args);
    }
}
