package com.novelagent.canon.application;

import com.novelagent.platform.support.Text;
import static com.novelagent.platform.support.Text.hasText;

import com.novelagent.writing.domain.FactProposal;
import java.util.Arrays;

/** Keeps each entity name paired with its own ID; a transfer's sender is not the item being tracked. */
public record FactEntityReference(String type, String name, String id) {
    public static FactEntityReference subject(FactProposal fact) {
        var p = fact.payload();
        if (p == null) return new FactEntityReference("CHARACTER", fact.subject(), null);
        String type = "STATE_CHANGE".equals(fact.factType())
                ? first(p.stateEntityType(), p.entityType(), "CHARACTER") : first(p.entityType(), "CHARACTER");
        if ("RELATION_CHANGE".equals(fact.factType())) return new FactEntityReference("CHARACTER",
                first(p.sourceEntityName(), p.entityName(), fact.subject()), first(p.sourceEntityId(), p.entityId()));
        if ("KNOWLEDGE_CHANGE".equals(fact.factType())) return new FactEntityReference("CHARACTER",
                first(p.characterName(), p.entityName(), fact.subject()), first(p.characterId(), p.entityId()));
        if (hasText(p.entityName())) return new FactEntityReference(type, p.entityName(), p.entityId());
        if (hasText(p.characterName())) return new FactEntityReference(type, p.characterName(), p.characterId());
        if (hasText(p.sourceEntityName())) return new FactEntityReference(type, p.sourceEntityName(), p.sourceEntityId());
        return new FactEntityReference(type, fact.subject(), first(p.entityId(), p.characterId(), p.sourceEntityId()));
    }
    public static FactEntityReference target(FactProposal fact) {
        var p = fact.payload();
        return new FactEntityReference("CHARACTER", p == null ? fact.object() : first(p.targetEntityName(), fact.object()),
                p == null ? null : p.targetEntityId());
    }
    private static String first(String... values) { return Arrays.stream(values).filter(Text::hasText).findFirst().orElse(null); }
}
