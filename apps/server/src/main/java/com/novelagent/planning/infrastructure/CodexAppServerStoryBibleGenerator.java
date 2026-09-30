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
public class CodexAppServerStoryBibleGenerator implements StoryBibleGenerator {
    private static final String WORKFLOW_TYPE = "STORY_BIBLE";
    private final CodexAppServerClient client;
    private final CodexAgentSessionRepository sessionRepository;
    private final StoryBibleModelPromptFactory promptFactory;
    private final StoryBibleModelOutputParser outputParser;
    private final JsonNode outputSchema;
    private final AgentRunRecorder runs;

    public CodexAppServerStoryBibleGenerator(CodexAppServerClient client,
            CodexAgentSessionRepository sessionRepository, StoryBibleModelPromptFactory promptFactory,
            StoryBibleModelOutputParser outputParser, StoryBibleOutputSchema outputSchema,
            AgentRunRecorder runs) {
        this.client = client;
        this.sessionRepository = sessionRepository;
        this.promptFactory = promptFactory;
        this.outputParser = outputParser;
        this.outputSchema = outputSchema.value();
        this.runs = runs;
    }

    @Override public ModelProvider provider() { return ModelProvider.LOCAL_CODEX; }

    @Override
    public GeneratedStoryBible generate(UUID projectId, CreativeIntentSnapshot intent,
            StoryDirectionCandidate direction, StoryBibleContent previousBible, String authorInstruction) {
        CodexAgentSession session = sessionRepository.findByProjectIdAndWorkflowType(projectId, WORKFLOW_TYPE).orElse(null);
        if (session == null) {
            session = sessionRepository.saveAndFlush(CodexAgentSession.create(
                    projectId, WORKFLOW_TYPE, client.startThread(projectId, promptFactory.systemPrompt())));
        }
        else {
            try { client.resumeThread(session.getThreadId(), projectId, promptFactory.systemPrompt()); }
            catch (CodexAppServerException exception) {
                if (!exception.indicatesMissingThread()) throw exception;
                session.replaceThread(client.startThread(projectId, promptFactory.systemPrompt()));
                session = sessionRepository.saveAndFlush(session);
            }
        }
        String prompt = promptFactory.userPrompt(intent, direction, previousBible, authorInstruction);
        CodexAgentSession activeSession = session;
        CodexAppServerClient.TurnResult result = runs.record(projectId, WORKFLOW_TYPE, provider(),
                promptFactory.systemPrompt(), prompt,
                () -> client.runStructuredTurn(activeSession.getThreadId(), projectId, prompt, outputSchema));
        session.recordTurn(result.turnId());
        sessionRepository.saveAndFlush(session);
        return outputParser.parse(provider(), result.output());
    }
}
