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
public class CodexAppServerStoryDirectionGenerator implements StoryDirectionGenerator {

    private static final String WORKFLOW_TYPE = "STORY_DIRECTION";
    private final CodexAppServerClient client;
    private final CodexAgentSessionRepository sessionRepository;
    private final StoryDirectionModelPromptFactory promptFactory;
    private final StoryDirectionModelOutputParser outputParser;
    private final JsonNode outputSchema;
    private final AgentRunRecorder runs;

    public CodexAppServerStoryDirectionGenerator(
            CodexAppServerClient client,
            CodexAgentSessionRepository sessionRepository,
            StoryDirectionModelPromptFactory promptFactory,
            StoryDirectionModelOutputParser outputParser,
            StoryDirectionOutputSchema outputSchema, AgentRunRecorder runs) {
        this.client = client;
        this.sessionRepository = sessionRepository;
        this.promptFactory = promptFactory;
        this.outputParser = outputParser;
        this.outputSchema = outputSchema.value();
        this.runs = runs;
    }

    @Override
    public ModelProvider provider() {
        return ModelProvider.LOCAL_CODEX;
    }

    @Override
    public GeneratedStoryDirections generate(
            UUID projectId,
            CreativeIntentSnapshot intent,
            List<StoryDirectionCandidate> previousDirections,
            String authorInstruction) {
        CodexAgentSession session = sessionRepository
                .findByProjectIdAndWorkflowType(projectId, WORKFLOW_TYPE)
                .orElse(null);
        if (session == null) {
            String threadId = client.startThread(projectId, promptFactory.systemPrompt());
            session = sessionRepository.saveAndFlush(
                    CodexAgentSession.create(projectId, WORKFLOW_TYPE, threadId));
        }
        else {
            try {
                client.resumeThread(session.getThreadId(), projectId, promptFactory.systemPrompt());
            }
            catch (CodexAppServerException exception) {
                if (!exception.indicatesMissingThread()) {
                    throw exception;
                }
                session.replaceThread(client.startThread(projectId, promptFactory.systemPrompt()));
                session = sessionRepository.saveAndFlush(session);
            }
        }

        String prompt = promptFactory.userPrompt(intent, previousDirections, authorInstruction);
        CodexAgentSession activeSession = session;
        CodexAppServerClient.TurnResult result = runs.record(projectId, WORKFLOW_TYPE, provider(),
                promptFactory.systemPrompt(), prompt,
                () -> client.runStructuredTurn(activeSession.getThreadId(), projectId, prompt, outputSchema));
        session.recordTurn(result.turnId());
        sessionRepository.saveAndFlush(session);
        return outputParser.parse(provider(), result.output());
    }
}
