package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningPromptConstraintTest {
    @Test
    void scenesRemainFreeTextInTheOutlineProtocol() {
        var chapterSchema = new OutlineOutputSchema(new ObjectMapper()).value()
                .at("/properties/content/properties/arcs/items/properties/chapters/items");
        assertThat(chapterSchema.path("required")).anyMatch(value -> value.asText().equals("sceneOutline"));
        assertThat(chapterSchema.at("/properties/sceneOutline/type").asText()).isEqualTo("string");
        assertThat(chapterSchema.at("/properties/sceneOutline").size()).isEqualTo(1);
        assertThat(chapterSchema.path("properties").has("sceneOutlineNeedsUpdate")).isFalse();
    }

    @Test
    void selectedOutlineAndBibleAreIncludedWithoutLosingFields() throws Exception {
        var mapper = new ObjectMapper();
        var chapter = new ChapterPlan(40, "Final chapter", "Lead", "Choose", "Final event",
                "Reveal", "Ending", 2400, 3600);
        var selected = new OutlineContent("Selected", "Premise", "Structure", "Pace", 110000, 130000,
                List.of(new OutlineArc(1, "Arc", "Goal", "Conflict", "Turn", "Result",
                        110000, 130000, List.of(chapter))));
        var bible = new StoryBibleContent("Story", "Theme", "World", List.of(), "Lead", "Growth",
                List.of(), List.of(), "Conflict", "Cost", "Voice", "Ending", List.of(), List.of("Open question"));
        var budget = new OutlineWordBudget(120000, 110000, 130000, 3, 40, 3000, 2400, 3600);
        String prompt = new OutlineModelPromptFactory(mapper).userPrompt(bible, budget, selected, "Revise");
        String outlineJson = prompt.split("选定的基准大纲：", 2)[1]
                .split("\\R\\R【调整原则】", 2)[0].trim();
        String bibleJson = prompt.split("【已发布故事圣经（完整 JSON）】", 2)[1]
                .split("【篇幅参考】", 2)[0].trim();
        assertThat(mapper.readValue(outlineJson, OutlineContent.class)).isEqualTo(selected);
        assertThat(mapper.readValue(bibleJson, StoryBibleContent.class)).isEqualTo(bible);
    }
}
