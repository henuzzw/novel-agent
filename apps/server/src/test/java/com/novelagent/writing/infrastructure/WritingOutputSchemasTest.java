package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WritingOutputSchemasTest {

    @Test
    void reviewSchemaUsesCodexCompatibleTypedFactPayload() {
        var schema = new WritingOutputSchemas(new ObjectMapper()).review();
        var fact = schema.at("/properties/factProposals/items");
        var payload = fact.at("/properties/payload");

        assertThat(fact.at("/properties/factType/enum")).hasSize(6);
        assertThat(payload.path("oneOf").isMissingNode()).isTrue();
        assertThat(payload.at("/properties/entityName/type")).hasSize(2);
        assertThat(payload.at("/properties/participants/type")).hasSize(2);
        assertThat(payload.at("/properties/knowledgeType/enum")).hasSize(5);
        assertThat(payload.at("/properties/fieldKey/enum")).hasSize(9);
        assertThat(payload.at("/properties/stateEntityType/enum")).hasSize(3);
        assertThat(payload.at("/required")).hasSize(25);
        assertThat(payload.path("additionalProperties").asBoolean()).isFalse();
        assertThat(fact.at("/properties/confidence/minimum").asInt()).isZero();
        assertThat(fact.at("/properties/confidence/maximum").asInt()).isEqualTo(1);
    }
}
