package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.OutlineGenerator;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CodexAppServerOutlineGenerator implements OutlineGenerator {
    private static final String WORKFLOW_TYPE = "OUTLINE";
    private final StructuredModelGateway models;
    private final OutlineModelPromptFactory prompts;
    private final OutlineModelOutputParser parser;
    private final JsonNode schema;

    public CodexAppServerOutlineGenerator(StructuredModelGateway models,
            OutlineModelPromptFactory prompts, OutlineModelOutputParser parser, OutlineOutputSchema schema) {
        this.models = models; this.prompts = prompts; this.parser = parser;
        this.schema = schema.value();
    }
    @Override public ModelProvider provider() { return ModelProvider.LOCAL_CODEX; }

    @Override
    public GeneratedOutline generate(UUID projectId, StoryBibleContent bible, OutlineWordBudget budget,
            OutlineContent previousOutline, String authorInstruction, CreativeStrategyPolicy policy) {
        String prompt = prompts.userPrompt(bible, budget, previousOutline, authorInstruction, policy);
        String output = models.request(projectId, WORKFLOW_TYPE, provider(), prompts.systemPrompt(), prompt,
                schema, "novel_outline", 16_000, CodexSessionPolicy.NEW_THREAD);
        return parser.parse(provider(), output);
    }
}
