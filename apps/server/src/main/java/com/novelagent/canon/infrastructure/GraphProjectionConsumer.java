package com.novelagent.canon.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.writing.domain.FactProposal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class GraphProjectionConsumer {
    private static final String UPSERT_FACT_CYPHER = """
            MERGE (entity:StoryEntity {projectId: $projectId, name: $subject})
            MERGE (fact:StoryFact {projectId: $projectId, factId: $factId})
            SET fact.type = $type, fact.subject = $subject, fact.predicate = $predicate,
                fact.object = $object, fact.evidence = $evidence, fact.canonVersion = $canonVersion
            MERGE (entity)-[:ASSERTS]->(fact)
            """;

    private final ObjectMapper mapper;
    private final CanonCommitRepository commits;
    private final JdbcTemplate jdbc;
    private final Neo4jClient neo4j;
    private final ProjectionCheckpointStore checkpoints;

    GraphProjectionConsumer(ObjectMapper mapper, CanonCommitRepository commits, JdbcTemplate jdbc,
            Neo4jClient neo4j, ProjectionCheckpointStore checkpoints) {
        this.mapper = mapper;
        this.commits = commits;
        this.jdbc = jdbc;
        this.neo4j = neo4j;
        this.checkpoints = checkpoints;
    }

    @KafkaListener(topics = "${app.kafka.canon-topic}", groupId = "novel-neo4j-projector-v4")
    public void project(String message) throws Exception {
        CanonProjectionEvent event = CanonProjectionEvent.parse(message, mapper);
        if (checkpoints.isCompleted(event.eventId(), ProjectionCheckpointStore.ProjectionType.NEO4J)) return;
        CanonCommit commit = commits.findById(event.commitId()).orElseThrow();
        for (FactProposal fact : commit.getAcceptedFacts()) upsertFact(event, fact);
        projectTypedCanon(event);
        updateWatermark(event);
        checkpoints.markCompleted(event.eventId(), ProjectionCheckpointStore.ProjectionType.NEO4J);
    }

    private void upsertFact(CanonProjectionEvent event, FactProposal fact) {
        neo4j.query(UPSERT_FACT_CYPHER).bindAll(Map.of(
                "projectId", event.projectId().toString(), "subject", fact.subject(),
                "factId", fact.id(), "type", fact.factType(), "predicate", fact.predicate(),
                "object", fact.object(), "evidence", fact.evidence(),
                "canonVersion", event.canonVersion())).run();
    }

    private void projectTypedCanon(CanonProjectionEvent event) {
        UUID commitId = event.commitId();
        projectRows("""
                SELECT id, entity_type, canonical_name, status, canon_version_from, evidence_ref
                  FROM story_entity WHERE source_commit_id = ?
                """, commitId, """
                MERGE (entity:StoryEntity {projectId: $projectId, entityId: $id})
                SET entity.entityType = $entityType, entity.name = $canonicalName, entity.status = $status,
                    entity.canonVersion = $canonVersionFrom, entity.evidence = $evidenceRef
                """, event);
        projectRows("""
                SELECT id, title, summary, story_time_text, narrative_chapter, importance,
                       canon_version_from, evidence_ref
                  FROM story_event WHERE source_commit_id = ?
                """, commitId, """
                MERGE (item:StoryEvent {projectId: $projectId, eventId: $id})
                SET item.title = $title, item.summary = $summary, item.storyTime = $storyTimeText,
                    item.chapter = $narrativeChapter, item.importance = $importance,
                    item.canonVersion = $canonVersionFrom, item.evidence = $evidenceRef
                """, event);
        projectRows("""
                SELECT s.id, s.entity_id, e.canonical_name, e.entity_type, s.field_key,
                       s.before_value::text, s.after_value::text, s.story_time_text,
                       s.narrative_chapter, s.canon_version_from, s.evidence_ref
                  FROM entity_state_change s JOIN story_entity e ON e.id = s.entity_id
                 WHERE s.source_commit_id = ?
                """, commitId, """
                MERGE (entity:StoryEntity {projectId: $projectId, entityId: $entityId})
                SET entity.name = $canonicalName, entity.entityType = $entityType
                MERGE (change:StateChange {projectId: $projectId, changeId: $id})
                SET change.field = $fieldKey, change.before = $beforeValue, change.after = $afterValue,
                    change.storyTime = $storyTimeText, change.chapter = $narrativeChapter,
                    change.canonVersion = $canonVersionFrom, change.evidence = $evidenceRef
                MERGE (entity)-[:HAS_STATE_CHANGE]->(change)
                """, event);
        projectRows("""
                SELECT r.id, r.source_entity_id, source.canonical_name AS source_name,
                       r.target_entity_id, target.canonical_name AS target_name,
                       r.relation_type, r.attributes::text, r.canon_version_from, r.evidence_ref
                  FROM story_relationship r
                  JOIN story_entity source ON source.id = r.source_entity_id
                  JOIN story_entity target ON target.id = r.target_entity_id
                 WHERE r.source_commit_id = ?
                """, commitId, """
                MERGE (source:StoryEntity {projectId: $projectId, entityId: $sourceEntityId})
                SET source.name = $sourceName
                MERGE (target:StoryEntity {projectId: $projectId, entityId: $targetEntityId})
                SET target.name = $targetName
                MERGE (source)-[relation:RELATED_TO {relationshipId: $id}]->(target)
                SET relation.type = $relationType, relation.attributes = $attributes,
                    relation.canonVersion = $canonVersionFrom, relation.evidence = $evidenceRef
                """, event);
        projectRows("""
                SELECT k.id, k.character_id, entity.canonical_name, k.fact_id,
                       fact.subject_text, fact.predicate, fact.object_text, k.knowledge_type,
                       k.belief_truth, k.confidence, k.narrative_chapter,
                       k.canon_version_from, k.evidence_ref
                  FROM character_knowledge k
                  JOIN story_entity entity ON entity.id = k.character_id
                  JOIN story_fact fact ON fact.id = k.fact_id
                 WHERE k.source_commit_id = ?
                """, commitId, """
                MERGE (character:StoryEntity {projectId: $projectId, entityId: $characterId})
                SET character.name = $canonicalName, character.entityType = 'CHARACTER'
                MERGE (fact:StoryFact {projectId: $projectId, stableFactId: $factId})
                SET fact.subject = $subjectText, fact.predicate = $predicate, fact.object = $objectText
                MERGE (character)-[knowledge:KNOWS {knowledgeId: $id}]->(fact)
                SET knowledge.type = $knowledgeType, knowledge.truth = $beliefTruth,
                    knowledge.confidence = $confidence, knowledge.chapter = $narrativeChapter,
                    knowledge.canonVersion = $canonVersionFrom, knowledge.evidence = $evidenceRef
                """, event);
        projectRows("""
                SELECT id, title, target_effect, current_status, planned_resolve_chapter,
                       canon_version_from, evidence_ref
                  FROM foreshadow WHERE source_commit_id = ?
                """, commitId, """
                MERGE (item:Foreshadow {projectId: $projectId, foreshadowId: $id})
                SET item.title = $title, item.targetEffect = $targetEffect, item.status = $currentStatus,
                    item.plannedResolveChapter = $plannedResolveChapter,
                    item.canonVersion = $canonVersionFrom, item.evidence = $evidenceRef
                """, event);
    }

    private void projectRows(String sql, UUID commitId, String cypher, CanonProjectionEvent event) {
        for (Map<String, Object> row : jdbc.queryForList(sql, commitId)) {
            Map<String, Object> parameters = new HashMap<>();
            parameters.put("projectId", event.projectId().toString());
            row.forEach((key, value) -> parameters.put(camelCase(key), neo4jValue(value)));
            neo4j.query(cypher).bindAll(parameters).run();
        }
    }

    private void updateWatermark(CanonProjectionEvent event) {
        neo4j.query("""
                MERGE (project:NovelProject {projectId: $projectId})
                SET project.projectionVersion = $canonVersion, project.projectedAt = datetime()
                """).bindAll(Map.of("projectId", event.projectId().toString(),
                        "canonVersion", event.canonVersion())).run();
    }

    private Object neo4jValue(Object value) {
        return value instanceof UUID uuid ? uuid.toString() : value;
    }

    private String camelCase(String value) {
        StringBuilder result = new StringBuilder();
        boolean upper = false;
        for (char character : value.toCharArray()) {
            if (character == '_') upper = true;
            else {
                result.append(upper ? Character.toUpperCase(character) : Character.toLowerCase(character));
                upper = false;
            }
        }
        return result.toString();
    }
}
