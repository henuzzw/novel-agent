package com.novelagent.planning.application;

import static com.alibaba.cloud.ai.graph.StateGraph.END;
import static com.alibaba.cloud.ai.graph.StateGraph.START;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;
import static com.novelagent.platform.support.GraphStateCodec.required;
import static com.novelagent.platform.support.Text.isBlank;

import com.novelagent.modelaccess.domain.ModelProvider;
import com.alibaba.cloud.ai.graph.CompiledGraph;
import com.alibaba.cloud.ai.graph.KeyStrategyFactoryBuilder;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.StateGraph;
import com.alibaba.cloud.ai.graph.exception.GraphStateException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.platform.support.GraphStateCodec;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class OutlineGenerationWorkflow {
    private final OutlineGeneratorRegistry registry;
    private final GraphStateCodec codec;
    private final CompiledGraph graph;

    public OutlineGenerationWorkflow(OutlineGeneratorRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.codec = new GraphStateCodec(mapper, "Agent Graph ");
        this.graph = compile();
    }

    public GeneratedOutline generate(UUID projectId, StoryBibleContent bible, OutlineWordBudget budget,
            ModelProvider provider, OutlineContent previousOutline, String instruction, CreativeStrategyPolicy policy) {
        OverAllState result = graph.invoke(Map.of("projectId", projectId.toString(), "bible", codec.write(bible),
                        "budget", codec.write(budget), "provider", provider.name(),
                        "previousOutline", previousOutline == null ? "" : codec.write(previousOutline),
                        "instruction", instruction == null ? "" : instruction, "policy", codec.write(policy)))
                .orElseThrow(() -> new IllegalStateException("分层大纲 Agent 工作流未返回结果"));
        return codec.read(required(result, "generated"), GeneratedOutline.class);
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
        codec.read(required(state, "bible"), StoryBibleContent.class);
        OutlineWordBudget budget = codec.read(required(state, "budget"), OutlineWordBudget.class);
        if (budget.acceptableMinWords() >= budget.acceptableMaxWords()) throw new IllegalArgumentException("字数区间无效");
        ModelProvider.valueOf(required(state, "provider"));
        codec.read(required(state, "policy"), CreativeStrategyPolicy.class);
        return Map.of();
    }

    private Map<String, Object> generateOutline(OverAllState state) {
        UUID projectId = UUID.fromString(required(state, "projectId"));
        StoryBibleContent bible = codec.read(required(state, "bible"), StoryBibleContent.class);
        OutlineWordBudget budget = codec.read(required(state, "budget"), OutlineWordBudget.class);
        ModelProvider provider = ModelProvider.valueOf(required(state, "provider"));
        String instruction = state.value("instruction", "");
        String previousJson = state.value("previousOutline", "");
        OutlineContent previousOutline = previousJson.isBlank() ? null : codec.read(previousJson, OutlineContent.class);
        return Map.of("generated", codec.write(registry.require(provider).generate(projectId, bible, budget,
                previousOutline, instruction.isBlank() ? null : instruction,
                codec.read(required(state, "policy"), CreativeStrategyPolicy.class))));
    }

    private Map<String, Object> validateOutput(OverAllState state) {
        GeneratedOutline generated = codec.read(required(state, "generated"), GeneratedOutline.class);
        if (isBlank(generated.generatorType()) || generated.content() == null
                || generated.content().arcs() == null || generated.content().arcs().isEmpty()
                || generated.content().chapterCount() == 0) {
            throw new IllegalStateException("模型未返回完整的分层大纲");
        }
        return Map.of();
    }
}
