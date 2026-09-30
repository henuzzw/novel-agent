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
public class CodexAppServerOutlineGenerator implements OutlineGenerator {
    private static final String WORKFLOW_TYPE = "OUTLINE";
    private final CodexAppServerClient client;
    private final CodexAgentSessionRepository sessions;
    private final OutlineModelPromptFactory prompts;
    private final OutlineModelOutputParser parser;
    private final JsonNode schema;
    private final AgentRunRecorder runs;

    public CodexAppServerOutlineGenerator(CodexAppServerClient client, CodexAgentSessionRepository sessions,
            OutlineModelPromptFactory prompts, OutlineModelOutputParser parser, OutlineOutputSchema schema,
            AgentRunRecorder runs) {
        this.client = client; this.sessions = sessions; this.prompts = prompts; this.parser = parser;
        this.schema = schema.value(); this.runs = runs;
    }
    @Override public ModelProvider provider() { return ModelProvider.LOCAL_CODEX; }

    @Override
    public GeneratedOutline generate(UUID projectId, StoryBibleContent bible, OutlineWordBudget budget,
            OutlineContent previousOutline, String authorInstruction) {
        CodexAgentSession session = sessions.findByProjectIdAndWorkflowType(projectId, WORKFLOW_TYPE).orElse(null);
        String threadId = client.startThread(projectId, prompts.systemPrompt());
        if (session == null) {
            session = CodexAgentSession.create(projectId, WORKFLOW_TYPE, threadId);
        }
        else {
            session.replaceThread(threadId);
        }
        session = sessions.saveAndFlush(session);
        String prompt = prompts.userPrompt(bible, budget, previousOutline, authorInstruction);
        CodexAgentSession activeSession = session;
        CodexAppServerClient.TurnResult result = runs.record(projectId, WORKFLOW_TYPE, provider(),
                prompts.systemPrompt(), prompt,
                () -> client.runStructuredTurn(activeSession.getThreadId(), projectId, prompt, schema));
        session.recordTurn(result.turnId());
        sessions.saveAndFlush(session);
        return parser.parse(provider(), result.output());
    }
}
