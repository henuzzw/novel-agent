package com.novelagent.planning.infrastructure;

import com.novelagent.planning.application.GeneratedStoryDirections;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.StoryDirectionGenerator;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;

@Component
public class DeepSeekStoryDirectionGenerator implements StoryDirectionGenerator {

    private final DeepSeekStructuredOutputClient client;
    private final StoryDirectionModelPromptFactory promptFactory;
    private final StoryDirectionModelOutputParser outputParser;
    private final JsonNode outputSchema;
    private final AgentRunRecorder runs;

    public DeepSeekStoryDirectionGenerator(
            DeepSeekStructuredOutputClient client,
            StoryDirectionModelPromptFactory promptFactory,
            StoryDirectionModelOutputParser outputParser,
            StoryDirectionOutputSchema outputSchema, AgentRunRecorder runs) {
        this.client = client;
        this.promptFactory = promptFactory;
        this.outputParser = outputParser;
        this.outputSchema = outputSchema.value();
        this.runs = runs;
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.DEEPSEEK;
    }

    @Override
    public GeneratedStoryDirections generate(
            UUID projectId,
            CreativeIntentSnapshot intent,
            List<StoryDirectionCandidate> previousDirections,
            String authorInstruction) {
        String prompt = promptFactory.userPrompt(intent, previousDirections, authorInstruction);
        String output = runs.record(projectId, "STORY_DIRECTION", provider(),
                promptFactory.systemPrompt(), prompt,
                () -> client.request("story_directions", promptFactory.systemPrompt(), prompt, outputSchema, 4_000));
        return outputParser.parse(provider(), output);
    }
}
