package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.planning.application.GeneratedStoryBible;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.StoryBibleGenerator;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekStoryBibleGenerator implements StoryBibleGenerator {
    private final DeepSeekStructuredOutputClient client;
    private final StoryBibleModelPromptFactory promptFactory;
    private final StoryBibleModelOutputParser outputParser;
    private final JsonNode outputSchema;
    private final AgentRunRecorder runs;

    public DeepSeekStoryBibleGenerator(DeepSeekStructuredOutputClient client,
            StoryBibleModelPromptFactory promptFactory, StoryBibleModelOutputParser outputParser,
            StoryBibleOutputSchema outputSchema, AgentRunRecorder runs) {
        this.client = client;
        this.promptFactory = promptFactory;
        this.outputParser = outputParser;
        this.outputSchema = outputSchema.value();
        this.runs = runs;
    }

    @Override public ModelProvider provider() { return ModelProvider.DEEPSEEK; }

    @Override
    public GeneratedStoryBible generate(UUID projectId, CreativeIntentSnapshot intent,
            StoryDirectionCandidate direction, StoryBibleContent previousBible, String authorInstruction) {
        String prompt = promptFactory.userPrompt(intent, direction, previousBible, authorInstruction);
        String output = runs.record(projectId, "STORY_BIBLE", provider(),
                promptFactory.systemPrompt(), prompt,
                () -> client.request("story_bible", promptFactory.systemPrompt(), prompt, outputSchema, 6_000));
        return outputParser.parse(provider(), output);
    }
}
