package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublishedMemoryPolicyTest {
    private final UUID character = UUID.randomUUID();
    private final EntityCatalogContext catalog = catalog(character, "江澈", List.of("小澈"));

    @Test
    void defaultsAllSixFactTypesToAcceptanceWithoutConfidenceOrLiteralSummaryGates() {
        for (String type : List.of("ENTITY_UPSERT", "STATE_CHANGE", "EVENT_CREATE", "RELATION_CHANGE", "KNOWLEDGE_CHANGE", "FORESHADOW_CHANGE")) {
            var fact = fact(type, "江澈", "沈秋", null, "他没有解释，但沈秋已经听到了真相。");
            assertThat(PublishedMemoryPolicy.autoAcceptable(fact, catalog, catalog)).as(type).isTrue();
        }
    }

    @Test
    void acceptsNewNamedEntitiesAndResolvedPronounsButNotUnknownPronouns() {
        assertThat(accept(fact("ENTITY_UPSERT", "陈默", "学生", null, "陈默推门进来。"))).isTrue();
        assertThat(accept(fact("STATE_CHANGE", "她", "教室", null, "她走进教室。"))).isFalse();
        assertThat(accept(fact("STATE_CHANGE", "我", "教室", null, "我走进教室。"))).isFalse();
        var resolved = payload(Map.of("entityId", character.toString()));
        assertThat(accept(fact("STATE_CHANGE", "他", "教室", resolved, "他走进教室。"))).isTrue();
        assertThat(accept(fact("ENTITY_UPSERT", "门边的教室", "地点", payload(Map.of("entityType", "LOCATION")), "我从教室出来。"))).isTrue();
    }

    @Test
    void checksBothRelationshipReferencesAndRejectsForeignOrInvalidIds() {
        assertThat(accept(fact("RELATION_CHANGE", "江澈", "她", null, "江澈信任她。"))).isFalse();
        for (String id : List.of("not-a-uuid", UUID.randomUUID().toString())) {
            assertThat(accept(fact("RELATION_CHANGE", "江澈", "沈秋", payload(Map.of("targetEntityId", id)), "江澈信任沈秋。"))).isFalse();
        }
        assertThat(accept(fact("ENTITY_UPSERT", "江澈", "学生", payload(Map.of("entityType", "ITEM", "entityId", character.toString())), "江澈是学生。"))).isFalse();
    }
    @Test void itemStateDoesNotMistakeTheSendersIdForTheItemId() {
        var transfer = payload(Map.of("entityName", "江澈送出的巧克力", "stateEntityType", "ITEM",
                "sourceEntityName", "江澈", "sourceEntityId", character.toString()));
        var fact = fact("STATE_CHANGE", "巧克力", "沈秋收下", transfer, "她把巧克力收进桌肚。");
        assertThat(FactEntityReference.subject(fact).id()).isNull();
        assertThat(FactEntityReference.subject(fact).type()).isEqualTo("ITEM");
        assertThat(accept(fact)).isTrue();
    }

    @Test
    void checksCanonicalNamesBeforeAliasesAndDetectsConcurrentIdentityChanges() {
        var collision = new EntityCatalogContext(List.of(catalog.entities().getFirst(),
                entry(UUID.randomUUID(), "陈默", List.of("小澈", "江澈"))));
        assertThat(PublishedMemoryPolicy.autoAcceptable(fact("ENTITY_UPSERT", "江澈", "学生", null, "江澈是学生。"), catalog, collision)).isTrue();
        assertThat(PublishedMemoryPolicy.autoAcceptable(fact("ENTITY_UPSERT", "小澈", "学生", null, "小澈是学生。"), catalog, collision)).isFalse();
        assertThat(PublishedMemoryPolicy.autoAcceptable(fact("ENTITY_UPSERT", "江澈", "学生", null, "江澈是学生。"), catalog,
                catalog(UUID.randomUUID(), "江澈", List.of()))).isFalse();
        assertThat(PublishedMemoryPolicy.autoAcceptable(fact("ENTITY_UPSERT", "江澈", "学生", null, "江澈是学生。"), EntityCatalogContext.empty(), catalog)).isTrue();
    }

    @Test
    void stillRequiresActualContinuousEvidenceAndUniqueItemIds() {
        var fact = fact("KNOWLEDGE_CHANGE", "江澈", "真相", null, "江澈知道了真相。");
        PublishedMemoryPolicy.validate(List.of(fact), fact.evidence());
        assertThatThrownBy(() -> PublishedMemoryPolicy.validate(List.of(fact), "江澈不知道真相。"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PublishedMemoryPolicy.validate(List.of(fact, fact), fact.evidence()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private boolean accept(FactProposal fact) { return PublishedMemoryPolicy.autoAcceptable(fact, catalog, catalog); }
    private static TypedFactPayload payload(Map<String, String> fields) { return new ObjectMapper().convertValue(fields, TypedFactPayload.class); }
    static FactProposal fact(String type, String subject, String object, TypedFactPayload payload, String evidence) {
        return new FactProposal(type, type, subject, "概括后的状态", object, evidence, null, payload, FactDecision.PENDING);
    }
    private static EntityCatalogContext catalog(UUID id, String name, List<String> aliases) {
        return new EntityCatalogContext(List.of(entry(id, name, aliases)));
    }
    private static EntityCatalogContext.EntityCatalogEntry entry(UUID id, String name, List<String> aliases) {
        return new EntityCatalogContext.EntityCatalogEntry(id, "CHARACTER", name, aliases, 0, false);
    }
}
