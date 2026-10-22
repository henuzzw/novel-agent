package com.novelagent.modelaccess.infrastructure;

import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.ModelResult;
import com.novelagent.memory.application.ModelContextProperties;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Existing App Server credentials and threads, with exact material deduplication. */
@Component
public class LegacyCodexExecutor extends AbstractModelExecutor {
    private final CodexAppServerClient client;
    private final LegacyCodexPromptGateway gateway;

    public LegacyCodexExecutor(AgentRunRecorder runs, ModelContextProperties context,
            CodexAppServerClient client, LegacyCodexPromptGateway gateway) {
        super(runs, context);
        this.client = client;
        this.gateway = gateway;
    }

    @Override public Entry entry() { return Entry.LEGACY_CODEX; }
    @Override protected EffectiveSettings effectiveSettings() { return client.effectiveSettings(); }
    @Override protected PreparedCall prepare(Request request, EffectiveSettings settings) {
        var prepared = gateway.prepare(request.projectId(), request.workflow(), request.sessionKey(), request.system(),
                request.user(), request.policy(), settings, request.revision());
        String system = prepared.instructions(request.system());
        return new PreparedCall() {
            @Override public String system() { return system; }
            @Override public String input() { return prepared.wireInput(); }
            @Override public String policy() { return "APP_SERVER_DELTA_" + request.policy().name(); }
            @Override public ModelResult send(Consumer<String> progress) {
                return prepared.run(system, settings, request.maxOutputTokens(), request.wireSchemaOrNull(), progress);
            }
            @Override public void accept(ModelResult result) { prepared.accept((CodexAppServerClient.TurnResult) result); }
            @Override public void close() { prepared.close(); }
        };
    }
}
