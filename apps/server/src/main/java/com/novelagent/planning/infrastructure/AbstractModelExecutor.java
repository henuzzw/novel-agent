package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.node.NullNode;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.ContextBudget;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.ModelResult;
import com.novelagent.agent.application.AgentRunRecorder.RequestSnapshot;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;
import java.util.UUID;
import java.util.function.Consumer;

/** Shared lifecycle; adapters own authentication, context preparation and transport only. */
public abstract class AbstractModelExecutor {
    public enum Entry { LEGACY_CODEX, PROJECT_CODEX, DEEPSEEK, CHATGPT_HTTP }
    private final AgentRunRecorder runs;
    private final StructuredRequestBudget budget;

    protected AbstractModelExecutor(AgentRunRecorder runs, ModelContextProperties context) {
        this.runs = runs;
        this.budget = new StructuredRequestBudget(context);
    }

    public abstract Entry entry();
    protected abstract EffectiveSettings effectiveSettings();
    protected abstract PreparedCall prepare(Request request, EffectiveSettings settings);

    public final String execute(Request request, Consumer<String> saveOutput) {
        var selected = effectiveSettings();
        var settings = new EffectiveSettings(request.provider(), selected.model(), selected.effort(), selected.version());
        try (var call = prepare(request, settings)) {
            var actualBudget = budget.requireCapacity(request.provider(), call.system(), call.input(),
                    NullNode.getInstance(), request.maxOutputTokens());
            var snapshot = RequestSnapshot.capture(settings, call.system(), call.input(), NullNode.getInstance(),
                    "PLAIN_TEXT", request.maxOutputTokens(), call.policy())
                    .withContextBudget(call.contextBudget() == null ? actualBudget : call.contextBudget());
            var decoded = new java.util.concurrent.atomic.AtomicReference<String>();
            runs.record(request.projectId(), request.workflow(), request.provider(), call.system(), call.input(), snapshot,
                    () -> call.send(runs.progressSink()), result -> {
                        String output = request.protocol().decode(result.output());
                        if (saveOutput != null) saveOutput.accept(output);
                        // Failed parsing or business persistence must not acknowledge context delivery.
                        call.accept(result);
                        decoded.set(output);
                    });
            return decoded.get();
        }
    }

    public record Request(UUID projectId, String workflow, ModelProvider provider, String system, String user,
            String schemaName, PlainTextOutputProtocol protocol, int maxOutputTokens,
            CodexSessionPolicy policy, String revision, boolean sharedPlanning) {
        public String sessionKey() { return sharedPlanning ? PlanningConversationPolicy.KEY : workflow; }
    }

    protected interface PreparedCall extends AutoCloseable {
        String system();
        String input();
        String policy();
        default ContextBudget contextBudget() { return null; }
        ModelResult send(Consumer<String> progress);
        default void accept(ModelResult result) { }
        @Override default void close() { }
    }
}
