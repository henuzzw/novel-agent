package com.novelagent.canon.application;

import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import java.util.List;
import java.util.Set;

/** Conservative indexing policy; confidence alone never authorizes an inferred fact. */
public class PublishedMemoryPolicy {
    private static final Set<String> TYPES = Set.of("ENTITY_UPSERT", "STATE_CHANGE", "EVENT_CREATE",
            "RELATION_CHANGE", "KNOWLEDGE_CHANGE", "FORESHADOW_CHANGE");
    private static final Set<String> STATE_FIELDS = Set.of("character.location", "character.physical_state",
            "character.emotional_state", "character.current_goal", "character.alive_status");
    public static void validate(List<FactProposal> facts, String body) {
        if (facts == null || facts.size() > 100) throw new IllegalArgumentException("记忆条目数量无效");
        var ids = new java.util.HashSet<String>();
        for (var fact : facts) {
            if (fact == null || fact.id() == null || fact.id().isBlank() || fact.id().length() > 100
                    || !ids.add(fact.id()) || !TYPES.contains(fact.factType())
                    || blank(fact.subject()) || blank(fact.predicate()) || blank(fact.object())
                    || blank(fact.evidence()) || !body.contains(fact.evidence())) {
                throw new IllegalArgumentException("记忆条目缺少有效来源、唯一编号或逐字正文证据");
            }
        }
    }
    public static FactProposal decide(FactProposal fact, FactDecision decision) {
        return new FactProposal(fact.id(), fact.factType(), fact.subject(), fact.predicate(), fact.object(),
                fact.evidence(), fact.confidence(), fact.payload(), decision);
    }
    public static boolean unambiguous(FactProposal fact, EntityCatalogContext catalog) {
        if (fact.confidence() == null || !Double.isFinite(fact.confidence()) || fact.confidence() < .95 || fact.confidence() > 1
                || !Set.of("ENTITY_UPSERT", "STATE_CHANGE", "EVENT_CREATE").contains(fact.factType())) return false;
        var evidence = fact.evidence();
        if (!evidence.contains(fact.subject()) || !evidence.contains(fact.predicate())
                || !evidence.contains(fact.object())
                || List.of("可能", "也许", "猜测", "传闻", "据说", "如果", "假如", "以为", "骗", "谎", "不曾", "没有", "并未")
                    .stream().anyMatch(evidence::contains)) return false;
        var payload = fact.payload();
        if (payload == null || payload.entityName() == null || !payload.entityName().equals(fact.subject())) return false;
        var matches = catalog.entities().stream().filter(entity -> entity.name().equals(fact.subject())
                || entity.aliases().contains(fact.subject())).toList();
        if (matches.size() != 1) return false;
        var entity = matches.getFirst();
        String type = "STATE_CHANGE".equals(fact.factType()) && !blank(payload.stateEntityType())
                ? payload.stateEntityType() : blank(payload.entityType()) ? "CHARACTER" : payload.entityType();
        if (!entity.type().equals(type) || (!blank(payload.entityId())
                && !entity.id().toString().equals(payload.entityId()))) return false;
        // Additional semantic claims and future/knowledge/relationship payloads require author review.
        if (!blank(payload.sourceEntityName()) || !blank(payload.targetEntityName()) || !blank(payload.characterName())
                || !blank(payload.sourceEntityId()) || !blank(payload.targetEntityId()) || !blank(payload.characterId())
                || !blank(payload.relationType()) || !blank(payload.knowledgeType()) || !blank(payload.beliefTruth())
                || !blank(payload.statement()) || !blank(payload.foreshadowTitle()) || !blank(payload.targetEffect())
                || !blank(payload.foreshadowStatus()) || payload.plannedResolveChapter() != null
                || (payload.participants() != null && !payload.participants().isEmpty())) return false;
        if (!supported(payload.storyTime(), evidence) || !supported(payload.beforeValue(), evidence)
                || !supported(payload.afterValue(), evidence) || !supported(payload.eventTitle(), evidence)
                || !supported(payload.eventSummary(), evidence)) return false;
        return !"STATE_CHANGE".equals(fact.factType()) || (payload.fieldKey() != null && STATE_FIELDS.contains(payload.fieldKey())
                && fact.object().equals(payload.afterValue()));
    }
    private static boolean supported(String value, String evidence) { return blank(value) || evidence.contains(value); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
