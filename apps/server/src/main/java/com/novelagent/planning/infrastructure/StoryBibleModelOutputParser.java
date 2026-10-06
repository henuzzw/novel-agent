package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.GeneratedStoryBible;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StoryBibleModelOutputParser {
    private final ObjectMapper objectMapper;

    public StoryBibleModelOutputParser(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public GeneratedStoryBible parse(ModelProvider provider, String rawOutput) {
        try {
            ModelOutput output = objectMapper.readValue(stripCodeFence(rawOutput), ModelOutput.class);
            if (output.content() == null || output.content().characterBlueprints().isEmpty()
                    || output.content().characterBlueprints().stream().noneMatch(value -> "PROTAGONIST".equals(value.role()))) {
                throw new IllegalArgumentException("模型输出缺少主角人物底稿");
            }
            return new GeneratedStoryBible(provider.name(), normalized(output.content()), safe(output.changeSummary()));
        }
        catch (Exception exception) {
            throw new IllegalArgumentException("模型返回的故事圣经格式不合法", exception);
        }
    }

    private static StoryBibleContent normalized(StoryBibleContent value) {
        return new StoryBibleContent(required(value.logline(), "logline"), required(value.theme(), "theme"),
                required(value.worldSetting(), "worldSetting"), safe(value.worldRules()),
                required(value.protagonist(), "protagonist"), required(value.protagonistArc(), "protagonistArc"),
                safe(value.supportingCharacters()), safe(value.relationshipDynamics()),
                required(value.centralConflict(), "centralConflict"), required(value.stakes(), "stakes"),
                required(value.narrativeStyle(), "narrativeStyle"), required(value.endingDirection(), "endingDirection"),
                safe(value.hardConstraints()), safe(value.openQuestions()), value.characterBlueprints(), value.readerExperiencePlans());
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("模型输出缺少字段：" + field);
        return value.trim();
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values.stream().filter(v -> v != null && !v.isBlank()).map(String::trim).toList();
    }

    private static String stripCodeFence(String value) {
        String result = value == null ? "" : value.trim();
        if (result.startsWith("```")) {
            result = result.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        return result;
    }

    private record ModelOutput(StoryBibleContent content, List<String> changeSummary) {
    }
}
