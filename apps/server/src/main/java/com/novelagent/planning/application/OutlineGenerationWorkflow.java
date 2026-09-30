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
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutlineGenerationWorkflow {
    private final OutlineGeneratorRegistry registry;
    private final ObjectMapper mapper;
    private final CompiledGraph graph;

    public OutlineGenerationWorkflow(OutlineGeneratorRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
        this.graph = compile();
    }

    public GeneratedOutline generate(UUID projectId, StoryBibleContent bible, OutlineWordBudget budget,
            ModelProvider provider, OutlineContent previousOutline, String instruction) {
        OverAllState result = graph.invoke(Map.of("projectId", projectId.toString(), "bible", write(bible),
                        "budget", write(budget), "provider", provider.name(),
                        "previousOutline", previousOutline == null ? "" : write(previousOutline),
                        "instruction", instruction == null ? "" : instruction))
                .orElseThrow(() -> new IllegalStateException("分层大纲 Agent 工作流未返回结果"));
        return read(required(result, "generated"), GeneratedOutline.class);
    }

    private CompiledGraph compile() {
        try {
            return new StateGraph("outline-generation", new KeyStrategyFactoryBuilder().build())
                    .addNode("validate_input", node_async(this::validateInput))
                    .addNode("generate_outline", node_async(this::generateOutline))
                    .addNode("validate_output", node_async(this::validateOutput))
                    .addEdge(START, "validate_input").addEdge("validate_input", "generate_outline")
                    .addEdge("generate_outline", "validate_output").addEdge("validate_output", END).compile();
        }
        catch (GraphStateException exception) { throw new IllegalStateException("分层大纲 Agent Graph 配置无效", exception); }
    }

    private Map<String, Object> validateInput(OverAllState state) {
        UUID.fromString(required(state, "projectId"));
        read(required(state, "bible"), StoryBibleContent.class);
        OutlineWordBudget budget = read(required(state, "budget"), OutlineWordBudget.class);
        if (budget.acceptableMinWords() >= budget.acceptableMaxWords()) throw new IllegalArgumentException("字数区间无效");
        ModelProvider.valueOf(required(state, "provider"));
        return Map.of();
    }

    private Map<String, Object> generateOutline(OverAllState state) {
        UUID projectId = UUID.fromString(required(state, "projectId"));
        StoryBibleContent bible = read(required(state, "bible"), StoryBibleContent.class);
        OutlineWordBudget budget = read(required(state, "budget"), OutlineWordBudget.class);
        ModelProvider provider = ModelProvider.valueOf(required(state, "provider"));
        String instruction = state.value("instruction", "");
        String previousJson = state.value("previousOutline", "");
        OutlineContent previousOutline = previousJson.isBlank() ? null : read(previousJson, OutlineContent.class);
        return Map.of("generated", write(registry.require(provider).generate(projectId, bible, budget,
                previousOutline, instruction.isBlank() ? null : instruction)));
    }

    private Map<String, Object> validateOutput(OverAllState state) {
        GeneratedOutline generated = read(required(state, "generated"), GeneratedOutline.class);
        if (generated.generatorType() == null || generated.generatorType().isBlank() || generated.content() == null
                || generated.content().arcs() == null || generated.content().arcs().isEmpty()
                || generated.content().chapterCount() == 0) {
            throw new IllegalStateException("模型未返回完整的分层大纲");
        }
        return Map.of();
    }

    private static String required(OverAllState state, String key) {
        return state.value(key, String.class).orElseThrow(() -> new IllegalStateException("Agent Graph 状态缺少字段：" + key));
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent Graph 状态序列化失败", exception); }
    }
    private <T> T read(String value, Class<T> type) {
        try { return mapper.readValue(value, type); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Agent Graph 状态反序列化失败", exception); }
    }
}
