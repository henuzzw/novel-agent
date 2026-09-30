package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import org.junit.jupiter.api.Test;

class PlanningModelOutputParserTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parsesStoryBibleWithChangeSummary() {
        String output = """
                {"content":{"logline":"故事","theme":"主题","worldSetting":"世界",
                "worldRules":[],"protagonist":"主角","protagonistArc":"弧光",
                "supportingCharacters":[],"relationshipDynamics":[],"centralConflict":"冲突",
                "stakes":"代价","narrativeStyle":"文风","endingDirection":"结局",
                "hardConstraints":[],"openQuestions":[]},
                "changeSummary":["补充了主角的行动动机。"]}
                """;

        var result = new StoryBibleModelOutputParser(mapper).parse(ModelProvider.DEEPSEEK, output);

        assertThat(result.content().logline()).isEqualTo("故事");
        assertThat(result.changeSummary()).containsExactly("补充了主角的行动动机。");
    }

    @Test
    void parsesOutlineWithChangeSummary() {
        String output = """
                {"content":{"title":"大纲","premise":"前提","structureSummary":"结构",
                "pacingStrategy":"节奏","suggestedMinWords":90000,"suggestedMaxWords":110000,
                "arcs":[{"ordinal":1,"title":"第一卷","objective":"目标","mainConflict":"冲突",
                "turningPoint":"转折","outcome":"结果","suggestedMinWords":90000,
                "suggestedMaxWords":110000,"chapters":[{"number":1,"title":"第一章","pov":"主角",
                "objective":"目标","coreEvent":"事件","reveal":"揭示","endingHook":"钩子",
                "suggestedMinWords":2500,"suggestedMaxWords":3500,"status":"PLANNED"}]}]},
                "changeSummary":["调整了第一卷的关键转折。"]}
                """;

        var result = new OutlineModelOutputParser(mapper).parse(ModelProvider.LOCAL_CODEX, output);

        assertThat(result.content().chapterCount()).isOne();
        assertThat(result.changeSummary()).containsExactly("调整了第一卷的关键转折。");
    }
}
