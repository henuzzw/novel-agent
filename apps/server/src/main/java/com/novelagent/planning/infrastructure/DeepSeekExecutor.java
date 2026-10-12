package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.node.NullNode;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.ModelResult;
import com.novelagent.memory.application.ModelContextProperties;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Stateless input: never substitutes inaccessible Codex thread or file references. */
@Component
public class DeepSeekExecutor extends AbstractModelExecutor {
    private final DeepSeekStructuredOutputClient client;

    public DeepSeekExecutor(AgentRunRecorder runs, ModelContextProperties context, DeepSeekStructuredOutputClient client) {
        super(runs, context);
        this.client = client;
    }

    @Override public Entry entry() { return Entry.DEEPSEEK; }
    @Override protected EffectiveSettings effectiveSettings() { return client.effectiveSettings(); }
    @Override protected PreparedCall prepare(Request request, EffectiveSettings settings) {
        return new PreparedCall() {
            @Override public String system() { return request.system(); }
            @Override public String input() { return request.user(); }
            @Override public String policy() { return "STATELESS"; }
            @Override public ModelResult send(Consumer<String> progress) {
                return client.request(request.schemaName(), request.system(), request.user(), NullNode.getInstance(),
                        request.maxOutputTokens(), settings);
            }
        };
    }
}
