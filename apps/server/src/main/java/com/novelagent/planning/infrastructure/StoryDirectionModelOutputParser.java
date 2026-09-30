package com.novelagent.planning.infrastructure;

import com.novelagent.planning.application.GeneratedStoryDirections;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class StoryDirectionModelOutputParser {

    private final ObjectMapper objectMapper;

    public StoryDirectionModelOutputParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public GeneratedStoryDirections parse(ModelProvider provider, String rawOutput) {
        try {
            String json = stripCodeFence(rawOutput);
            ModelOutput output = objectMapper.readValue(json, ModelOutput.class);
            if (output.directions() == null || output.directions().size() != 3) {
                throw new IllegalArgumentException("模型必须返回三个故事方向");
            }
            List<StoryDirectionCandidate> candidates = output.directions().stream()
                    .map(this::toCandidate)
                    .toList();
            return new GeneratedStoryDirections(
                    provider.name(),
                    candidates,
                    safe(output.questionsForAuthor()),
                    safe(output.changeSummary()));
        }
        catch (Exception exception) {
            throw new IllegalArgumentException("模型返回的故事方向格式不合法", exception);
        }
    }

    private StoryDirectionCandidate toCandidate(ModelCandidate value) {
        requireText(value.title(), "title");
        requireText(value.premise(), "premise");
        return new StoryDirectionCandidate(
                UUID.randomUUID(),
                value.title(),
                value.premise(),
                requireText(value.centralConflict(), "centralConflict"),
                requireText(value.protagonistArc(), "protagonistArc"),
                requireText(value.structure(), "structure"),
                requireText(value.endingDirection(), "endingDirection"),
                requireText(value.audienceFit(), "audienceFit"),
                safe(value.strengths()),
                safe(value.risks()),
                safe(value.distinctiveFeatures()));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("模型输出缺少字段：" + field);
        }
        return value.trim();
    }

    private static List<String> safe(List<String> values) {
        return values == null ? new ArrayList<>() : values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .toList();
    }

    private static String stripCodeFence(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```(?:json)?\\s*", "");
            trimmed = trimmed.replaceFirst("\\s*```$", "");
        }
        return trimmed;
    }

    private record ModelOutput(List<ModelCandidate> directions, List<String> questionsForAuthor,
            List<String> changeSummary) {
    }

    private record ModelCandidate(
            String title,
            String premise,
            String centralConflict,
            String protagonistArc,
            String structure,
            String endingDirection,
            String audienceFit,
            List<String> strengths,
            List<String> risks,
            List<String> distinctiveFeatures) {
    }
}
