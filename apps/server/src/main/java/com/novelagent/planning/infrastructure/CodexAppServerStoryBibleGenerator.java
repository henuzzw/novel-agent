package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.application.GeneratedStoryBible;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.StoryBibleGenerator;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class CodexAppServerStoryBibleGenerator implements StoryBibleGenerator {
    private static final String WORKFLOW_TYPE = "STORY_BIBLE";
    private final StructuredModelGateway models;
    private final StoryBibleModelPromptFactory promptFactory;
    private final StoryBibleModelOutputParser outputParser;
    private final JsonNode outputSchema;

    public CodexAppServerStoryBibleGenerator(StructuredModelGateway models,
            StoryBibleModelPromptFactory promptFactory,
            StoryBibleModelOutputParser outputParser, StoryBibleOutputSchema outputSchema) {
        this.models = models;
        this.promptFactory = promptFactory;
        this.outputParser = outputParser;
        this.outputSchema = outputSchema.value();
    }

    @Override public ModelProvider provider() { return ModelProvider.LOCAL_CODEX; }

    @Override
    public GeneratedStoryBible generate(UUID projectId, CreativeIntentSnapshot intent,
            StoryDirectionCandidate direction, StoryBibleContent previousBible, String authorInstruction) {
        String prompt = promptFactory.userPrompt(intent, direction, previousBible, authorInstruction);
        String output = models.request(projectId, WORKFLOW_TYPE, provider(), promptFactory.systemPrompt(), prompt,
                outputSchema, "story_bible", 10_000, CodexSessionPolicy.REUSE_THREAD);
        return outputParser.parse(provider(), output);
    }
}
