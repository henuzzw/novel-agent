package com.novelagent.planning.application;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StoryBibleGenerationWorkflow {
    private static final String PROJECT_ID = "projectId";
    private static final String INTENT_JSON = "intentJson";
    private static final String DIRECTION_JSON = "directionJson";
    private static final String PROVIDER = "provider";
    private static final String AUTHOR_INSTRUCTION = "authorInstruction";
    private static final String PREVIOUS_BIBLE_JSON = "previousBibleJson";
    private static final String GENERATED_JSON = "generatedJson";

    private final StoryBibleGeneratorRegistry registry;
    private final ObjectMapper objectMapper;
    private final CompiledGraph graph;

    public StoryBibleGenerationWorkflow(StoryBibleGeneratorRegistry registry, ObjectMapper objectMapper) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.graph = compileGraph();
    }

    public GeneratedStoryBible generate(UUID projectId, CreativeIntentSnapshot intent,
            StoryDirectionCandidate direction, ModelProvider provider, StoryBibleContent previousBible,
            String authorInstruction) {
        OverAllState result = graph.invoke(Map.of(
                        PROJECT_ID, projectId.toString(),
                        INTENT_JSON, writeJson(intent),
                        DIRECTION_JSON, writeJson(direction),
                        PROVIDER, provider.name(),
                        PREVIOUS_BIBLE_JSON, previousBible == null ? "" : writeJson(previousBible),
                        AUTHOR_INSTRUCTION, authorInstruction == null ? "" : authorInstruction))
                .orElseThrow(() -> new IllegalStateException("故事圣经 Agent 工作流未返回结果"));
        return readJson(require(result, GENERATED_JSON, String.class), GeneratedStoryBible.class);
    }

    private CompiledGraph compileGraph() {
        try {
            return new StateGraph("story-bible-generation", new KeyStrategyFactoryBuilder().build())
                    .addNode("validate_input", node_async(this::validateInput))
                    .addNode("generate_bible", node_async(this::generateBible))
                    .addNode("validate_output", node_async(this::validateOutput))
                    .addEdge(START, "validate_input")
                    .addEdge("validate_input", "generate_bible")
                    .addEdge("generate_bible", "validate_output")
                    .addEdge("validate_output", END)
                    .compile();
        }
        catch (GraphStateException exception) {
            throw new IllegalStateException("故事圣经 Agent Graph 配置无效", exception);
        }
    }

    private Map<String, Object> validateInput(OverAllState state) {
        UUID.fromString(require(state, PROJECT_ID, String.class));
        readJson(require(state, INTENT_JSON, String.class), CreativeIntentSnapshot.class);
        readJson(require(state, DIRECTION_JSON, String.class), StoryDirectionCandidate.class);
        ModelProvider.valueOf(require(state, PROVIDER, String.class));
        return Map.of();
    }

    private Map<String, Object> generateBible(OverAllState state) {
        UUID projectId = UUID.fromString(require(state, PROJECT_ID, String.class));
        CreativeIntentSnapshot intent = readJson(require(state, INTENT_JSON, String.class), CreativeIntentSnapshot.class);
        StoryDirectionCandidate direction = readJson(require(state, DIRECTION_JSON, String.class), StoryDirectionCandidate.class);
        ModelProvider provider = ModelProvider.valueOf(require(state, PROVIDER, String.class));
        String instruction = state.value(AUTHOR_INSTRUCTION, "");
        String previousJson = state.value(PREVIOUS_BIBLE_JSON, "");
        StoryBibleContent previousBible = previousJson.isBlank()
                ? null
                : readJson(previousJson, StoryBibleContent.class);
        GeneratedStoryBible generated = registry.require(provider)
                .generate(projectId, intent, direction, previousBible, instruction.isBlank() ? null : instruction);
        return Map.of(GENERATED_JSON, writeJson(generated));
    }

    private Map<String, Object> validateOutput(OverAllState state) {
        GeneratedStoryBible generated = readJson(require(state, GENERATED_JSON, String.class), GeneratedStoryBible.class);
        if (generated.generatorType() == null || generated.generatorType().isBlank() || generated.content() == null) {
            throw new IllegalStateException("模型未返回完整的故事圣经");
        }
        if (generated.content().logline() == null || generated.content().logline().isBlank()
                || generated.content().theme() == null || generated.content().theme().isBlank()
                || generated.content().worldSetting() == null || generated.content().worldSetting().isBlank()
                || generated.content().centralConflict() == null || generated.content().centralConflict().isBlank()
                || generated.content().endingDirection() == null || generated.content().endingDirection().isBlank()) {
            throw new IllegalStateException("模型返回的故事圣经缺少必填内容");
        }
        return Map.of();
    }

    private static <T> T require(OverAllState state, String key, Class<T> type) {
        return state.value(key, type).orElseThrow(() -> new IllegalStateException("Agent Graph 状态缺少字段：" + key));
    }

    private String writeJson(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent Graph 状态序列化失败", exception); }
    }

    private <T> T readJson(String value, Class<T> type) {
        try { return objectMapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent Graph 状态反序列化失败", exception); }
    }
}
