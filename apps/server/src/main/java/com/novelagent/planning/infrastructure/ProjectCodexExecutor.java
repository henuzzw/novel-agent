package com.novelagent.planning.infrastructure;

import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.ModelResult;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.application.ChatGptDirectGateway;
import com.novelagent.modelaccess.application.CodexProjectGateway;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Preserves the OAuth project's material tool, leases and native Codex compaction. */
@Component
public class ProjectCodexExecutor extends AbstractModelExecutor {
    private final CodexProjectGateway gateway;
    private final ChatGptDirectGateway settings;

    public ProjectCodexExecutor(AgentRunRecorder runs, ModelContextProperties context,
            CodexProjectGateway gateway, ChatGptDirectGateway settings) {
        super(runs, context);
        this.gateway = gateway;
        this.settings = settings;
    }

    @Override public Entry entry() { return Entry.PROJECT_CODEX; }
    @Override protected EffectiveSettings effectiveSettings() { return settings.effectiveSettings(); }
    @Override protected PreparedCall prepare(Request request, EffectiveSettings settings) {
        boolean main = request.sharedPlanning() || request.workflow().equals("MANUSCRIPT")
                || request.workflow().equals("MANUSCRIPT_LOCAL_EDIT");
        var prepared = gateway.prepare(request.projectId(), main ? "NOVEL_MAIN" : request.workflow(),
                !main && request.policy() == CodexSessionPolicy.NEW_THREAD, request.user());
        String system = prepared.instructions(request.system());
        return new PreparedCall() {
            @Override public String system() { return system; }
            @Override public String input() { return prepared.wireInput(); }
            @Override public String policy() {
                return "SIWC_CODEX_" + (main ? "PROJECT_THREAD" : request.policy().name());
            }
            @Override public ModelResult send(Consumer<String> progress) {
                return prepared.run(system, settings, request.maxOutputTokens(), request.wireSchemaOrNull(), progress);
            }
            @Override public void accept(ModelResult result) { prepared.accept(); }
            @Override public void close() { prepared.close(); }
        };
    }
}
