package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.OutlineGenerator;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekOutlineGenerator implements OutlineGenerator {
    private final DeepSeekStructuredOutputClient client;
    private final OutlineModelPromptFactory prompts;
    private final OutlineModelOutputParser parser;
    private final JsonNode schema;
    private final AgentRunRecorder runs;

    public DeepSeekOutlineGenerator(DeepSeekStructuredOutputClient client, OutlineModelPromptFactory prompts,
            OutlineModelOutputParser parser, OutlineOutputSchema schema, AgentRunRecorder runs) {
        this.client = client; this.prompts = prompts; this.parser = parser; this.schema = schema.value(); this.runs = runs;
    }
    @Override public ModelProvider provider() { return ModelProvider.DEEPSEEK; }
    @Override public GeneratedOutline generate(UUID projectId, StoryBibleContent bible, OutlineWordBudget budget,
            OutlineContent previousOutline, String authorInstruction) {
        String prompt = prompts.userPrompt(bible, budget, previousOutline, authorInstruction);
        String output = runs.record(projectId, "OUTLINE", provider(), prompts.systemPrompt(), prompt,
                () -> client.request("novel_outline", prompts.systemPrompt(), prompt, schema, 16_000));
        return parser.parse(provider(), output);
    }
}
