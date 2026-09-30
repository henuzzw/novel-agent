package com.novelagent.writing.application;

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
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.canon.application.EntityCatalogContext;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.infrastructure.WritingGenerationGateway;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WritingGenerationWorkflow {
    private static final String PROJECT_ID = "projectId";
    private static final String BIBLE = "bible";
    private static final String ARC = "arc";
    private static final String CHAPTER = "chapter";
    private static final String CONTRACT = "contract";
    private static final String MANUSCRIPT = "manuscript";
    private static final String PREVIOUS_MANUSCRIPT = "previousManuscript";
    private static final String MEMORY = "memory";
    private static final String PROVIDER = "provider";
    private static final String INSTRUCTION = "instruction";
    private static final String GENERATED = "generated";
    private static final String ENTITY_CATALOG = "entityCatalog";

    private final WritingGenerationGateway gateway;
    private final ObjectMapper mapper;
    private final CompiledGraph contractGraph;
    private final CompiledGraph manuscriptGraph;
    private final CompiledGraph reviewGraph;

    public WritingGenerationWorkflow(WritingGenerationGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.contractGraph = compile("chapter-contract-generation", this::generateContractNode,
                this::validateContractOutput);
        this.manuscriptGraph = compile("manuscript-generation", this::generateManuscriptNode,
                this::validateManuscriptOutput);
        this.reviewGraph = compile("chapter-review-generation", this::generateReviewNode,
                this::validateReviewOutput);
    }

    public ChapterContractContent generateContract(UUID projectId, StoryBibleContent bible, OutlineArc arc,
            ChapterPlan chapter, NovelMemoryContext memory, ModelProvider provider, String instruction) {
        OverAllState result = invoke(contractGraph, Map.of(
                PROJECT_ID, projectId.toString(), BIBLE, write(bible), ARC, write(arc), CHAPTER, write(chapter),
                MEMORY, write(memory), PROVIDER, provider.name(), INSTRUCTION, value(instruction)));
        return read(required(result, GENERATED), ChapterContractContent.class);
    }

    public GeneratedManuscript generateManuscript(UUID projectId, StoryBibleContent bible, OutlineArc arc,
            ChapterPlan chapter, ChapterContractContent contract, NovelMemoryContext memory,
            ManuscriptContent previousManuscript, ModelProvider provider, String instruction) {
        OverAllState result = invoke(manuscriptGraph, Map.of(
                PROJECT_ID, projectId.toString(), BIBLE, write(bible), ARC, write(arc), CHAPTER, write(chapter),
                CONTRACT, write(contract), MEMORY, write(memory), PROVIDER, provider.name(),
                PREVIOUS_MANUSCRIPT, previousManuscript == null ? "" : write(previousManuscript),
                INSTRUCTION, value(instruction)));
        return read(required(result, GENERATED), GeneratedManuscript.class);
    }

    public ChapterReviewContent generateReview(UUID projectId, StoryBibleContent bible,
            ChapterContractContent contract, ManuscriptContent manuscript, NovelMemoryContext memory,
            EntityCatalogContext entityCatalog, ModelProvider provider, String instruction) {
        OverAllState result = invoke(reviewGraph, Map.of(
                PROJECT_ID, projectId.toString(), BIBLE, write(bible), CONTRACT, write(contract),
                MANUSCRIPT, write(manuscript), MEMORY, write(memory), ENTITY_CATALOG, write(entityCatalog),
                PROVIDER, provider.name(),
                INSTRUCTION, value(instruction)));
        return read(required(result, GENERATED), ChapterReviewContent.class);
    }

    private CompiledGraph compile(String name,
            com.alibaba.cloud.ai.graph.action.NodeAction generate,
            com.alibaba.cloud.ai.graph.action.NodeAction validateOutput) {
        try {
            return new StateGraph(name, new KeyStrategyFactoryBuilder().build())
                    .addNode("validate_input", node_async(this::validateInput))
                    .addNode("generate", node_async(generate))
                    .addNode("validate_output", node_async(validateOutput))
                    .addEdge(START, "validate_input")
                    .addEdge("validate_input", "generate")
                    .addEdge("generate", "validate_output")
                    .addEdge("validate_output", END)
                    .compile();
        } catch (GraphStateException exception) {
            throw new IllegalStateException("写作 Agent Graph 配置无效：" + name, exception);
        }
    }

    private Map<String, Object> validateInput(OverAllState state) {
        UUID.fromString(required(state, PROJECT_ID));
        read(required(state, BIBLE), StoryBibleContent.class);
        read(required(state, MEMORY), NovelMemoryContext.class);
        ModelProvider.valueOf(required(state, PROVIDER));
        return Map.of();
    }

    private Map<String, Object> generateContractNode(OverAllState state) {
        ChapterContractContent generated = gateway.contract(
                UUID.fromString(required(state, PROJECT_ID)),
                read(required(state, BIBLE), StoryBibleContent.class),
                read(required(state, ARC), OutlineArc.class),
                read(required(state, CHAPTER), ChapterPlan.class),
                read(required(state, MEMORY), NovelMemoryContext.class),
                ModelProvider.valueOf(required(state, PROVIDER)),
                optional(state, INSTRUCTION));
        return Map.of(GENERATED, write(generated));
    }

    private Map<String, Object> generateManuscriptNode(OverAllState state) {
        String previousJson = state.value(PREVIOUS_MANUSCRIPT, "");
        ManuscriptContent previous = previousJson.isBlank() ? null : read(previousJson, ManuscriptContent.class);
        GeneratedManuscript generated = gateway.manuscript(
                UUID.fromString(required(state, PROJECT_ID)),
                read(required(state, BIBLE), StoryBibleContent.class),
                read(required(state, ARC), OutlineArc.class),
                read(required(state, CHAPTER), ChapterPlan.class),
                read(required(state, CONTRACT), ChapterContractContent.class),
                read(required(state, MEMORY), NovelMemoryContext.class),
                previous,
                ModelProvider.valueOf(required(state, PROVIDER)),
                optional(state, INSTRUCTION));
        return Map.of(GENERATED, write(generated));
    }

    private Map<String, Object> generateReviewNode(OverAllState state) {
        ChapterReviewContent generated = gateway.review(
                UUID.fromString(required(state, PROJECT_ID)),
                read(required(state, BIBLE), StoryBibleContent.class),
                read(required(state, CONTRACT), ChapterContractContent.class),
                read(required(state, MANUSCRIPT), ManuscriptContent.class),
                read(required(state, MEMORY), NovelMemoryContext.class),
                read(required(state, ENTITY_CATALOG), EntityCatalogContext.class),
                ModelProvider.valueOf(required(state, PROVIDER)),
                optional(state, INSTRUCTION));
        return Map.of(GENERATED, write(generated));
    }

    private Map<String, Object> validateContractOutput(OverAllState state) {
        ChapterContractContent content = read(required(state, GENERATED), ChapterContractContent.class);
        requireText(content.chapterTitle(), "章节标题");
        requireText(content.objective(), "章节目标");
        if (content.suggestedMinWords() <= 0 || content.suggestedMaxWords() < content.suggestedMinWords()) {
            throw new IllegalStateException("章节合同字数区间无效");
        }
        return Map.of();
    }

    private Map<String, Object> validateManuscriptOutput(OverAllState state) {
        GeneratedManuscript generated = read(required(state, GENERATED), GeneratedManuscript.class);
        ManuscriptContent content = generated.content();
        if (content == null || generated.changeSummary() == null) {
            throw new IllegalStateException("模型未返回完整正文或修改说明");
        }
        requireText(content.title(), "正文标题");
        requireText(content.body(), "正文");
        requireText(content.summary(), "章节摘要");
        return Map.of();
    }

    private Map<String, Object> validateReviewOutput(OverAllState state) {
        ChapterReviewContent content = read(required(state, GENERATED), ChapterReviewContent.class);
        requireText(content.summary(), "审稿摘要");
        if (content.issues() == null || content.factProposals() == null) {
            throw new IllegalStateException("审稿结果缺少问题或候选事实列表");
        }
        return Map.of();
    }

    private OverAllState invoke(CompiledGraph graph, Map<String, Object> input) {
        return graph.invoke(input).orElseThrow(() -> new IllegalStateException("写作 Agent 工作流未返回结果"));
    }

    private static String required(OverAllState state, String key) {
        return state.value(key, String.class)
                .orElseThrow(() -> new IllegalStateException("Agent Graph 状态缺少字段：" + key));
    }

    private static String optional(OverAllState state, String key) {
        String value = state.value(key, "");
        return value.isBlank() ? null : value;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("模型输出缺少" + field);
        }
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("写作 Agent Graph 状态序列化失败", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("写作 Agent Graph 状态反序列化失败", exception);
        }
    }
}
