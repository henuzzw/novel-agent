package com.novelagent.planning.infrastructure;

import com.novelagent.planning.application.GeneratedStoryDirections;
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

    private final StructuredModelGateway models;
    private final StoryDirectionModelPromptFactory promptFactory;
    private final StoryDirectionModelOutputParser outputParser;
    private final JsonNode outputSchema;

    public DeepSeekStoryDirectionGenerator(
            StructuredModelGateway models,
            StoryDirectionModelPromptFactory promptFactory,
            StoryDirectionModelOutputParser outputParser,
            StoryDirectionOutputSchema outputSchema) {
        this.models = models;
        this.promptFactory = promptFactory;
        this.outputParser = outputParser;
        this.outputSchema = outputSchema.value();
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
        String output = models.request(projectId, "STORY_DIRECTION", provider(), promptFactory.systemPrompt(), prompt,
                outputSchema, "story_directions", 4_000, CodexSessionPolicy.REUSE_THREAD);
        return outputParser.parse(provider(), output);
    }
}
