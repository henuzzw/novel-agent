package com.novelagent.agent.tool;

import com.novelagent.memory.application.NovelMemoryContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

@Component
class RelatedCanonFactsTool implements NovelReadTool {
    private final Neo4jClient neo4j;

    RelatedCanonFactsTool(Neo4jClient neo4j) {
        this.neo4j = neo4j;
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
        List<NovelMemoryContext.GraphFact> facts = query(request, true, request.graphFactCandidateLimit());
        if (facts.isEmpty()) {
            facts = query(request, false, Math.min(4, request.graphFactCandidateLimit()));
        }
        return NovelToolResult.facts(facts);
    }

    private List<NovelMemoryContext.GraphFact> query(NovelToolRequest request, boolean relevantOnly, int limit) {
        String relevance = relevantOnly ? "AND ($query CONTAINS entity.name OR $query CONTAINS fact.object)" : "";
        var rows = neo4j.query("""
                MATCH (entity:StoryEntity {projectId: $projectId})-[:ASSERTS]->
                      (fact:StoryFact {projectId: $projectId})
                WHERE fact.canonVersion <= $canonVersion %s
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
                        "canonVersion", request.canonVersion(),
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
