package com.novelagent.canon.application;

import static com.novelagent.platform.support.Text.isBlank;

import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Published text authorizes derived memory; valid extractions are saved without an approval step. */
public class PublishedMemoryPolicy {
    private static final Set<String> TYPES = Set.of("ENTITY_UPSERT", "STATE_CHANGE", "EVENT_CREATE",
            "RELATION_CHANGE", "KNOWLEDGE_CHANGE", "FORESHADOW_CHANGE");
    private static final Set<String> ENTITY_TYPES = Set.of("CHARACTER", "LOCATION", "ORGANIZATION", "ITEM", "SECRET", "RULE");
    public static void validate(List<FactProposal> facts, String body) {
        if (facts == null || facts.size() > 100) throw new IllegalArgumentException("记忆条目数量无效");
        var ids = new HashSet<String>();
        for (var fact : facts) {
            if (fact == null || isBlank(fact.id()) || fact.id().length() > 100
                    || !ids.add(fact.id()) || !TYPES.contains(fact.factType())
                    || isBlank(fact.subject()) || isBlank(fact.predicate()) || isBlank(fact.object())
                    || isBlank(fact.evidence()) || !body.contains(fact.evidence())
                    || (fact.confidence() != null && (!Double.isFinite(fact.confidence())
                        || fact.confidence() < 0 || fact.confidence() > 1))) {
                throw new IllegalArgumentException("记忆条目缺少有效来源、唯一编号或逐字正文证据");
            }
        }
    }
    public static FactProposal decide(FactProposal fact, FactDecision decision) {
        return new FactProposal(fact.id(), fact.factType(), fact.subject(), fact.predicate(), fact.object(),
                fact.evidence(), fact.confidence(), fact.payload(), decision);
    }
    public static boolean autoAcceptable(FactProposal fact, EntityCatalogContext source, EntityCatalogContext current) {
        // Events and foreshadows have no entity foreign key in the materializer.
        if (Set.of("EVENT_CREATE", "FORESHADOW_CHANGE").contains(fact.factType())) return true;
        var subject = FactEntityReference.subject(fact);
        if (!stableReference(subject.type(), subject.name(), subject.id(), fact.evidence(), source, current)) return false;
        var target = FactEntityReference.target(fact);
        return !"RELATION_CHANGE".equals(fact.factType()) || stableReference(target.type(),
                target.name(), target.id(), fact.evidence(), source, current);
    }

    private static boolean stableReference(String type, String name, String id, String evidence,
            EntityCatalogContext source, EntityCatalogContext current) {
        String before = reference(type, name, id, evidence, source);
        String after = reference(type, name, id, evidence, current);
        // Another accepted item may have created this exact named entity while the model was running.
        return before != null && after != null && (before.equals(after) || before.startsWith("NEW:"));
    }

    /** Follow the materializer's requested-ID, canonical-name, then confirmed-alias resolution order. */
    private static String reference(String type, String name, String id, String evidence, EntityCatalogContext catalog) {
        if (isBlank(type) || isBlank(name)) return null;
        type = type.trim().toUpperCase(Locale.ROOT);
        name = name.trim();
        if (!ENTITY_TYPES.contains(type)) return null;
        String entityType = type, mention = name;
        if (!isBlank(id)) {
            UUID requested;
            try { requested = UUID.fromString(id); }
            catch (IllegalArgumentException invalid) { return null; }
            return catalog.entities().stream().filter(e -> e.id().equals(requested) && e.type().equals(entityType))
                    .map(e -> e.id().toString()).findFirst().orElse(null);
        }
        var typed = catalog.entities().stream().filter(e -> e.type().equals(entityType)).toList();
        var matches = typed.stream().filter(e -> e.name().equalsIgnoreCase(mention)).toList();
        if (matches.isEmpty()) matches = typed.stream().filter(e -> e.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(mention))).toList();
        if (matches.size() == 1) return matches.getFirst().id().toString();
        if (matches.size() > 1 || EntityResolutionService.isPronoun(mention)) return null;
        if (Set.of("CHARACTER", "ORGANIZATION").contains(type) && !evidence.contains(mention)) return null;
        return "NEW:" + entityType + ":" + mention;
    }
}
