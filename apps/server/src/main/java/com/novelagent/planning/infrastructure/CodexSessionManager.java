package com.novelagent.planning.infrastructure;

import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import java.util.UUID;

/**
 * Owns the persisted conversation lifecycle, not prompt construction or run recording.
 * Model waits remain outside database transactions. A failed turn is never recorded as completed.
 */
final class CodexSessionManager {
    private final CodexAppServerClient codex;
    private final CodexAgentSessionRepository sessions;

    CodexSessionManager(CodexAppServerClient codex, CodexAgentSessionRepository sessions) {
        this.codex = codex;
        this.sessions = sessions;
    }

    void recordTurn(CodexAgentSession session, String turnId) {
        session.recordTurn(turnId);
        sessions.saveAndFlush(session);
    }

    CodexAgentSession prepare(
            UUID projectId,
            String workflow,
            String systemPrompt,
            CodexSessionPolicy policy,
            EffectiveSettings settings,
            String promptRevision) {
        CodexAgentSession session = sessions.findByProjectIdAndWorkflowType(projectId, workflow).orElse(null);
        // A new/restored prompt revision must not inherit the previous conversation's instructions.
        if (session == null || policy == CodexSessionPolicy.NEW_THREAD
                || !session.matchesPromptRevision(promptRevision)) {
            String threadId = codex.startThread(projectId, systemPrompt, settings);
            if (session == null) {
                session = CodexAgentSession.create(projectId, workflow, threadId);
            } else {
                session.replaceThread(threadId);
            }
            session.usePromptRevision(promptRevision);
            return sessions.saveAndFlush(session);
        }
        try {
            codex.resumeThread(session.getThreadId(), projectId, systemPrompt, settings);
        } catch (CodexAppServerException exception) {
            if ("AUTHENTICATION".equals(CodexAppServerClient.failureCategory(exception.getMessage()))
                    || !exception.indicatesMissingThread()) {
                throw exception;
            }
            session.replaceThread(codex.startThread(projectId, systemPrompt, settings));
            session = sessions.saveAndFlush(session);
        }
        return session;
    }
}
