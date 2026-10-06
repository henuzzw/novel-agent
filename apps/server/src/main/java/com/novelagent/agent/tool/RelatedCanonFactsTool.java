package com.novelagent.agent.tool;

import com.novelagent.memory.application.NovelMemoryContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class RelatedCanonFactsTool implements NovelReadTool {
    private final Neo4jClient neo4j;
    private final JdbcTemplate jdbc;

    RelatedCanonFactsTool(Neo4jClient neo4j, JdbcTemplate jdbc) {
        this.neo4j = neo4j;
        this.jdbc = jdbc;
    }

    @Override
    public NovelToolName name() {
        return NovelToolName.GET_RELATED_CANON_FACTS;
    }

    @Override
    public boolean optional() {
        return true;
    }

    @Override
    public NovelToolResult execute(NovelToolRequest request) {
        List<Long> activeVersions = jdbc.queryForList("""
                SELECT canon_version FROM canon_commit
                 WHERE project_id = ? AND active = TRUE AND chapter_number < ? AND canon_version <= ?
                """, Long.class, request.projectId(), request.chapterNumber(), request.canonVersion());
        if (activeVersions.isEmpty()) return NovelToolResult.facts(List.of());
        List<NovelMemoryContext.GraphFact> facts = query(request, activeVersions, true, request.graphFactCandidateLimit());
        if (facts.isEmpty()) {
            facts = query(request, activeVersions, false, Math.min(4, request.graphFactCandidateLimit()));
        }
        return NovelToolResult.facts(facts);
    }

    private List<NovelMemoryContext.GraphFact> query(NovelToolRequest request, List<Long> activeVersions,
            boolean relevantOnly, int limit) {
        String relevance = relevantOnly ? "AND ($query CONTAINS entity.name OR $query CONTAINS fact.object)" : "";
        var rows = neo4j.query("""
                MATCH (entity:StoryEntity {projectId: $projectId})-[:ASSERTS]->
                      (fact:StoryFact {projectId: $projectId})
                WHERE fact.canonVersion IN $activeVersions %s
                RETURN fact.canonVersion AS canonVersion,
                       entity.name AS subject,
                       fact.predicate AS predicate,
                       fact.object AS object,
                       fact.evidence AS evidence
                ORDER BY fact.canonVersion DESC
                LIMIT $limit
                """.formatted(relevance))
                .bindAll(Map.of(
                        "projectId", request.projectId().toString(),
                        "activeVersions", activeVersions,
                        "query", request.query(),
                        "limit", limit))
                .fetch()
                .all();
        List<NovelMemoryContext.GraphFact> facts = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            facts.add(new NovelMemoryContext.GraphFact(
                    ((Number) row.get("canonVersion")).longValue(),
                    string(row.get("subject")),
                    string(row.get("predicate")),
                    string(row.get("object")),
                    string(row.get("evidence"))));
        }
        return List.copyOf(facts);
    }

    private static String string(Object value) {
        return value == null ? "" : value.toString();
    }
}
