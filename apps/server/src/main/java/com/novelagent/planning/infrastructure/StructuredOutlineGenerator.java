package com.novelagent.planning.infrastructure;

import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.planning.application.OutlineGenerator;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.util.UUID;

/** 通过统一结构化模型网关生成大纲；各模型提供方只需声明自身的 {@link ModelProvider}。 */
public abstract class StructuredOutlineGenerator implements OutlineGenerator {
    private static final String WORKFLOW_TYPE = "OUTLINE";
    private final ModelProvider provider;
    private final StructuredModelGateway models;
    private final OutlineModelPromptFactory prompts;
    private final OutlineModelOutputParser parser;
    private final JsonNode schema;

    protected StructuredOutlineGenerator(ModelProvider provider, StructuredModelGateway models,
            OutlineModelPromptFactory prompts, OutlineModelOutputParser parser, OutlineOutputSchema schema) {
        this.provider = provider; this.models = models; this.prompts = prompts; this.parser = parser;
        this.schema = schema.value();
    }
    @Override public final ModelProvider provider() { return provider; }

    @Override
    public final GeneratedOutline generate(UUID projectId, StoryBibleContent bible, OutlineWordBudget budget,
            OutlineContent previousOutline, String authorInstruction, CreativeStrategyPolicy policy) {
        String prompt = prompts.userPrompt(bible, budget, previousOutline, authorInstruction, policy);
        String output = models.request(projectId, WORKFLOW_TYPE, provider(), prompts.systemPrompt(), prompt,
                schema, "novel_outline", 16_000, CodexSessionPolicy.NEW_THREAD);
        return parser.parse(provider(), output);
    }
}
