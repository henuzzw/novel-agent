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
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class StoryDirectionGenerationWorkflow {

    private static final String PROJECT_ID = "projectId";
    private static final String INTENT_JSON = "intentJson";
    private static final String PROVIDER = "provider";
    private static final String AUTHOR_INSTRUCTION = "authorInstruction";
    private static final String PREVIOUS_DIRECTIONS_JSON = "previousDirectionsJson";
    private static final String GENERATED_JSON = "generatedJson";

    private final StoryDirectionGeneratorRegistry generatorRegistry;
    private final ObjectMapper objectMapper;
    private final CompiledGraph graph;

    public StoryDirectionGenerationWorkflow(
            StoryDirectionGeneratorRegistry generatorRegistry,
            ObjectMapper objectMapper) {
        this.generatorRegistry = generatorRegistry;
        this.objectMapper = objectMapper;
        this.graph = compileGraph();
    }

    public GeneratedStoryDirections generate(
            UUID projectId,
            CreativeIntentSnapshot intent,
            ModelProvider provider,
            List<StoryDirectionCandidate> previousDirections,
            String authorInstruction) {
        Map<String, Object> input = Map.of(
                PROJECT_ID, projectId.toString(),
                INTENT_JSON, writeJson(intent),
                PROVIDER, provider.name(),
                PREVIOUS_DIRECTIONS_JSON, previousDirections == null ? "" : writeJson(previousDirections),
                AUTHOR_INSTRUCTION, authorInstruction == null ? "" : authorInstruction);

        OverAllState result = graph.invoke(input)
                .orElseThrow(() -> new IllegalStateException("故事方向 Agent 工作流未返回结果"));
        String generatedJson = require(result, GENERATED_JSON, String.class);
        return readJson(generatedJson, GeneratedStoryDirections.class);
    }

    private CompiledGraph compileGraph() {
        try {
            return new StateGraph("story-direction-generation", new KeyStrategyFactoryBuilder().build())
                    .addNode("validate_input", node_async(this::validateInput))
                    .addNode("generate_candidates", node_async(this::generateCandidates))
                    .addNode("validate_output", node_async(this::validateOutput))
                    .addEdge(START, "validate_input")
                    .addEdge("validate_input", "generate_candidates")
                    .addEdge("generate_candidates", "validate_output")
                    .addEdge("validate_output", END)
                    .compile();
        }
        catch (GraphStateException exception) {
            throw new IllegalStateException("故事方向 Agent Graph 配置无效", exception);
        }
    }

    private Map<String, Object> validateInput(OverAllState state) {
        UUID.fromString(require(state, PROJECT_ID, String.class));
        CreativeIntentSnapshot intent = readJson(
                require(state, INTENT_JSON, String.class), CreativeIntentSnapshot.class);
        ModelProvider.valueOf(require(state, PROVIDER, String.class));
        if (intent.targetWords() == null || intent.targetWords() <= 0) {
            throw new IllegalArgumentException("目标字数必须大于 0");
        }
        return Map.of();
    }

    private Map<String, Object> generateCandidates(OverAllState state) {
        UUID projectId = UUID.fromString(require(state, PROJECT_ID, String.class));
        CreativeIntentSnapshot intent = readJson(
                require(state, INTENT_JSON, String.class), CreativeIntentSnapshot.class);
        ModelProvider provider = ModelProvider.valueOf(require(state, PROVIDER, String.class));
        String instruction = state.value(AUTHOR_INSTRUCTION, "");
        String previousJson = state.value(PREVIOUS_DIRECTIONS_JSON, "");
        List<StoryDirectionCandidate> previousDirections = previousJson.isBlank()
                ? List.of()
                : List.of(readJson(previousJson, StoryDirectionCandidate[].class));
        GeneratedStoryDirections generated = generatorRegistry.require(provider)
                .generate(projectId, intent, previousDirections, instruction.isBlank() ? null : instruction);
        return Map.of(GENERATED_JSON, writeJson(generated));
    }

    private Map<String, Object> validateOutput(OverAllState state) {
        GeneratedStoryDirections generated = readJson(
                require(state, GENERATED_JSON, String.class), GeneratedStoryDirections.class);
        if (generated.generatorType() == null || generated.generatorType().isBlank()) {
            throw new IllegalStateException("模型未返回生成器类型");
        }
        if (generated.directions() == null || generated.directions().size() != 3) {
            throw new IllegalStateException("模型必须返回 3 个故事方向");
        }
        if (generated.directions().stream().anyMatch(candidate -> candidate == null)) {
            throw new IllegalStateException("模型返回了空故事方向");
        }
        List<String> questions = generated.questionsForAuthor();
        if (questions == null) {
            throw new IllegalStateException("模型未返回作者问题列表");
        }
        return Map.of();
    }

    private static <T> T require(OverAllState state, String key, Class<T> type) {
        return state.value(key, type)
                .orElseThrow(() -> new IllegalStateException("Agent Graph 状态缺少字段：" + key));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent Graph 状态序列化失败", exception);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("Agent Graph 状态反序列化失败", exception);
        }
    }
}
