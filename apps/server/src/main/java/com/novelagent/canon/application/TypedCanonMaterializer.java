package com.novelagent.canon.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class TypedCanonMaterializer {
    private static final Map<String, String> STATE_FIELDS = Map.ofEntries(
            Map.entry("位置", "character.location"),
            Map.entry("所在地", "character.location"),
            Map.entry("身体状态", "character.physical_state"),
            Map.entry("健康状态", "character.physical_state"),
            Map.entry("情绪", "character.emotional_state"),
            Map.entry("心理状态", "character.emotional_state"),
            Map.entry("目标", "character.current_goal"),
            Map.entry("当前目标", "character.current_goal"),
            Map.entry("生死状态", "character.alive_status"),
            Map.entry("存活状态", "character.alive_status"));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final EntityResolutionService entityResolution;

    public TypedCanonMaterializer(JdbcTemplate jdbc, ObjectMapper mapper,
            EntityResolutionService entityResolution) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.entityResolution = entityResolution;
    }

    public void materialize(UUID projectId, int chapterNumber, UUID commitId, long canonVersion,
            List<FactProposal> facts) {
        for (FactProposal proposal : facts) {
            UUID subjectId = resolveSubject(projectId, commitId, canonVersion, proposal);
            UUID factId = insertFact(projectId, commitId, canonVersion, subjectId, proposal);
            switch (normalizedType(proposal.factType())) {
                case "EVENT", "EVENT_CREATE" -> insertEvent(projectId, chapterNumber, commitId,
                        canonVersion, factId, proposal);
                case "STATE", "STATE_CHANGE" -> stateField(proposal).ifPresent(field ->
                        insertState(projectId, chapterNumber, commitId, canonVersion, subjectId,
                                factId, field, proposal));
                case "RELATION", "RELATION_CHANGE" -> insertRelationship(projectId, commitId,
                        canonVersion, subjectId, factId, proposal);
                case "KNOWLEDGE", "KNOWLEDGE_CHANGE" -> insertKnowledge(projectId, chapterNumber,
                        commitId, canonVersion, subjectId, factId, proposal);
                case "FORESHADOW", "FORESHADOW_CHANGE" -> insertForeshadow(projectId, commitId,
                        canonVersion, factId, proposal);
                default -> {
                    // The generic story_fact remains authoritative until this type gets an explicit mapper.
                }
            }
        }
    }

    private UUID insertFact(UUID projectId, UUID commitId, long canonVersion, UUID subjectId,
            FactProposal proposal) {
        UUID factId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO story_fact
                    (id, project_id, proposal_id, fact_type, subject_entity_id, subject_text,
                     predicate, object_text, canon_version_from, source_commit_id, evidence_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, factId, projectId, proposal.id(), normalizedType(proposal.factType()), subjectId,
                normalizedText(proposal.subject()), normalizedText(proposal.predicate()),
                normalizedText(proposal.object()), canonVersion, commitId, proposal.evidence());
        return factId;
    }

    private void insertEvent(UUID projectId, int chapterNumber, UUID commitId, long canonVersion,
            UUID factId, FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        String title = hasText(payload == null ? null : payload.eventTitle())
                ? payload.eventTitle() : normalizedText(proposal.subject()) + normalizedText(proposal.predicate());
        String summary = hasText(payload == null ? null : payload.eventSummary())
                ? payload.eventSummary() : factSentence(proposal);
        String storyTime = payload == null ? null : payload.storyTime();
        jdbc.update("""
                INSERT INTO story_event
                    (id, project_id, source_fact_id, title, summary, story_time_text, narrative_chapter,
                     canon_version_from, source_commit_id, evidence_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, factId, truncate(title, 300), summary,
                storyTime, chapterNumber, canonVersion, commitId, proposal.evidence());
    }

    private void insertState(UUID projectId, int chapterNumber, UUID commitId, long canonVersion,
            UUID subjectId, UUID factId, String field, FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        String beforeValue = payload == null ? null : payload.beforeValue();
        String afterValue = payload != null && hasText(payload.afterValue())
                ? payload.afterValue() : normalizedText(proposal.object());
        String storyTime = payload == null ? null : payload.storyTime();
        jdbc.update("""
                INSERT INTO entity_state_change
                    (id, project_id, entity_id, source_fact_id, field_key, before_value, after_value,
                     story_time_text, narrative_chapter, canon_version_from, source_commit_id, evidence_ref)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, subjectId, factId, field,
                beforeValue == null ? null : json(beforeValue), json(afterValue), storyTime,
                chapterNumber, canonVersion, commitId, proposal.evidence());
    }

    private void insertRelationship(UUID projectId, UUID commitId, long canonVersion,
            UUID subjectId, UUID factId, FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        String targetName = hasText(payload == null ? null : payload.targetEntityName())
                ? payload.targetEntityName() : proposal.object();
        String relationType = hasText(payload == null ? null : payload.relationType())
                ? payload.relationType() : proposal.predicate();
        UUID targetId = entityResolution.resolve(projectId, commitId, canonVersion,
                proposal.id(), "TARGET", "CHARACTER", targetName,
                payload == null ? null : payload.targetEntityId(), proposal.evidence());
        if (subjectId.equals(targetId)) {
            return;
        }
        jdbc.update("""
                INSERT INTO story_relationship
                    (id, project_id, source_entity_id, target_entity_id, source_fact_id,
                     relation_type, canon_version_from, source_commit_id, evidence_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, subjectId, targetId, factId,
                truncate(normalizedText(relationType), 100), canonVersion, commitId,
                proposal.evidence());
    }

    private void insertKnowledge(UUID projectId, int chapterNumber, UUID commitId, long canonVersion,
            UUID characterId, UUID factId, FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        String knowledgeType = hasText(payload == null ? null : payload.knowledgeType())
                ? payload.knowledgeType() : "LEARNED";
        String beliefTruth = hasText(payload == null ? null : payload.beliefTruth())
                ? payload.beliefTruth() : "UNVERIFIED";
        double confidence = proposal.confidence() == null ? 0.7 : proposal.confidence();
        jdbc.update("""
                INSERT INTO character_knowledge
                    (id, project_id, character_id, fact_id, knowledge_type, belief_truth,
                     confidence, narrative_chapter, canon_version_from, source_commit_id, evidence_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, characterId, factId, knowledgeType, beliefTruth,
                confidence, chapterNumber, canonVersion, commitId, proposal.evidence());
    }

    private void insertForeshadow(UUID projectId, UUID commitId, long canonVersion, UUID factId,
            FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        String title = hasText(payload == null ? null : payload.foreshadowTitle())
                ? payload.foreshadowTitle() : normalizedText(proposal.subject()) + normalizedText(proposal.predicate());
        String targetEffect = hasText(payload == null ? null : payload.targetEffect())
                ? payload.targetEffect() : proposal.object();
        String status = hasText(payload == null ? null : payload.foreshadowStatus())
                ? payload.foreshadowStatus() : "PLANTED";
        Integer plannedChapter = payload == null ? null : payload.plannedResolveChapter();
        jdbc.update("""
                INSERT INTO foreshadow
                    (id, project_id, source_fact_id, title, target_effect, current_status,
                     planned_resolve_chapter, canon_version_from, source_commit_id, evidence_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, factId,
                truncate(title, 300), targetEffect, status, plannedChapter, canonVersion, commitId,
                proposal.evidence());
    }

    private Optional<String> stateField(String predicate) {
        String value = normalizedText(predicate);
        return STATE_FIELDS.entrySet().stream()
                .filter(entry -> value.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    private String subjectType(FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        if ("STATE_CHANGE".equals(proposal.factType())
                && hasText(payload == null ? null : payload.stateEntityType())) {
            return payload.stateEntityType();
        }
        return hasText(payload == null ? null : payload.entityType()) ? payload.entityType() : "CHARACTER";
    }

    private String subjectName(FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        if (payload != null) {
            if (hasText(payload.entityName())) return payload.entityName();
            if (hasText(payload.sourceEntityName())) return payload.sourceEntityName();
            if (hasText(payload.characterName())) return payload.characterName();
        }
        return proposal.subject();
    }

    private String subjectRequestedId(FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        if (payload == null) return null;
        if (hasText(payload.entityId())) return payload.entityId();
        if (hasText(payload.sourceEntityId())) return payload.sourceEntityId();
        if (hasText(payload.characterId())) return payload.characterId();
        return null;
    }

    private UUID resolveSubject(UUID projectId, UUID commitId, long canonVersion,
            FactProposal proposal) {
        String type = normalizedType(proposal.factType());
        if ("FORESHADOW".equals(type) || "FORESHADOW_CHANGE".equals(type)
                || "EVENT_CREATE".equals(type)) {
            return null;
        }
        return entityResolution.resolve(projectId, commitId, canonVersion,
                proposal.id(), "SUBJECT", subjectType(proposal), subjectName(proposal),
                subjectRequestedId(proposal), proposal.evidence());
    }

    private Optional<String> stateField(FactProposal proposal) {
        TypedFactPayload payload = proposal.payload();
        if (payload != null && hasText(payload.fieldKey())) {
            return Optional.of(payload.fieldKey());
        }
        return stateField(proposal.predicate());
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private String json(String value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("正史状态值无法序列化", exception);
        }
    }

    private String factSentence(FactProposal proposal) {
        return normalizedText(proposal.subject()) + " " + normalizedText(proposal.predicate())
                + " " + normalizedText(proposal.object());
    }

    private String normalizedType(String value) {
        return normalizedText(value).toUpperCase(Locale.ROOT);
    }

    private String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return "未命名";
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
