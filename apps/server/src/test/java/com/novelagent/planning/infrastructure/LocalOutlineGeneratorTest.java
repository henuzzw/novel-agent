package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalOutlineGeneratorTest {
    @Test
    void generatesHierarchyWithFuzzyCapacityRanges() {
        OutlineWordBudget budget = new OutlineWordBudget(120000, 110000, 130000, 2, 40, 3000, 2400, 3600);
        StoryBibleContent bible = new StoryBibleContent("故事", "主题", "世界", List.of(), "主角", "弧光",
                List.of(), List.of(), "冲突", "代价", "文风", "结局", List.of(), List.of());

        var result = new LocalOutlineGenerator().generate(UUID.randomUUID(), bible, budget, null, null);

        assertThat(result.content().arcs()).hasSize(2);
        assertThat(result.content().chapterCount()).isEqualTo(40);
        assertThat(result.content().arcs()).allSatisfy(arc -> {
            assertThat(arc.suggestedMinWords()).isEqualTo(55_000);
            assertThat(arc.suggestedMaxWords()).isEqualTo(65_000);
        });
    }
}
