package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.infrastructure.OutlineOutputSchema;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReaderExperienceSeedTest {
    @Test void legacyDocumentsDefaultToEmptyPlansAndNewSchemasUseExplicitPlanning() throws Exception {
        var mapper = new ObjectMapper();
        assertThat(mapper.readValue("{\"arcs\":[]}", OutlineContent.class).readerExperiencePlans()).isEmpty();
        assertThat(mapper.readValue("{}", StoryBibleContent.class).readerExperiencePlans()).isEmpty();
        for (var schema : List.of(new StoryBibleOutputSchema(mapper).value(), new OutlineOutputSchema(mapper).value())) {
            var plan = schema.path("properties").path("content").path("properties").path("readerExperiencePlans");
            assertThat(plan.path("maxItems").asInt()).isEqualTo(80);
            assertThat(plan.path("items").path("required")).hasSize(8);
        }
    }
    @Test void rejectsDuplicateKeysInvalidTypesAndNegativeChapters() {
        var seed = new ReaderExperienceSeed("note", "FORESHADOW", "纸条", "认出主人", "", "", "", null);
        assertThatThrownBy(() -> new OutlineContent("故事", "", "", "", 1, 2, List.of(), List.of(seed, seed)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReaderExperienceSeed("note", "PAYOFF", "纸条", "认出主人", "", "", "", 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReaderExperienceSeed("note", "FORESHADOW", "纸条", "认出主人", "", "", "", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
