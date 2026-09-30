package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.planning.application.GeneratedStoryDirections;
import com.novelagent.planning.application.ModelProvider;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;

class StoryDirectionModelOutputParserTest {

    private final StoryDirectionModelOutputParser parser =
            new StoryDirectionModelOutputParser(new ObjectMapper());

    @Test
    void parsesJsonWrappedInAMarkdownFence() {
        String output = """
                ```json
                {
                  "directions": [
                    {
                      "title": "方向一",
                      "premise": "前提一",
                      "centralConflict": "冲突一",
                      "protagonistArc": "成长一",
                      "structure": "结构一",
                      "endingDirection": "结局一",
                      "audienceFit": "受众一",
                      "strengths": ["优势一"],
                      "risks": ["风险一"],
                      "distinctiveFeatures": ["特色一"]
                    },
                    {
                      "title": "方向二",
                      "premise": "前提二",
                      "centralConflict": "冲突二",
                      "protagonistArc": "成长二",
                      "structure": "结构二",
                      "endingDirection": "结局二",
                      "audienceFit": "受众二",
                      "strengths": ["优势二"],
                      "risks": ["风险二"],
                      "distinctiveFeatures": ["特色二"]
                    },
                    {
                      "title": "方向三",
                      "premise": "前提三",
                      "centralConflict": "冲突三",
                      "protagonistArc": "成长三",
                      "structure": "结构三",
                      "endingDirection": "结局三",
                      "audienceFit": "受众三",
                      "strengths": ["优势三"],
                      "risks": ["风险三"],
                      "distinctiveFeatures": ["特色三"]
                    }
                  ],
                  "questionsForAuthor": ["更偏好哪种结局？"],
                  "changeSummary": ["将第二个方向的核心冲突改为公开竞争。"]
                }
                ```
                """;

        GeneratedStoryDirections result = parser.parse(ModelProvider.DEEPSEEK, output);

        assertThat(result.generatorType()).isEqualTo("DEEPSEEK");
        assertThat(result.directions()).hasSize(3);
        assertThat(result.directions()).extracting(direction -> direction.title())
                .containsExactly("方向一", "方向二", "方向三");
        assertThat(result.questionsForAuthor()).containsExactly("更偏好哪种结局？");
        assertThat(result.changeSummary()).containsExactly("将第二个方向的核心冲突改为公开竞争。");
    }
}
