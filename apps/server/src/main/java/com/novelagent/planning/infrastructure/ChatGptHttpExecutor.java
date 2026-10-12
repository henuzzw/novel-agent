package com.novelagent.planning.infrastructure;

import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.ContextBudget;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.ModelResult;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.application.ChatGptDirectGateway;
import com.novelagent.modelaccess.infrastructure.ChatGptResponsesClient;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Compatibility adapter for the explicitly selectable HTTP history transport. */
@Component
public class ChatGptHttpExecutor extends AbstractModelExecutor {
    private final ChatGptDirectGateway gateway;

    public ChatGptHttpExecutor(AgentRunRecorder runs, ModelContextProperties context, ChatGptDirectGateway gateway) {
        super(runs, context);
        this.gateway = gateway;
    }

    @Override public Entry entry() { return Entry.CHATGPT_HTTP; }
    @Override protected EffectiveSettings effectiveSettings() { return gateway.effectiveSettings(); }
    @Override protected PreparedCall prepare(Request request, EffectiveSettings settings) {
        var prepared = gateway.prepare(request.projectId(), request.sessionKey(), request.system(), request.user(),
                request.policy(), request.revision(), request.maxOutputTokens());
        return new PreparedCall() {
            @Override public String system() { return request.system(); }
            @Override public String input() { return prepared.wireInput(); }
            @Override public String policy() { return "SIWC_HTTP_" + request.policy().name(); }
            @Override public ContextBudget contextBudget() { return prepared.budget(); }
            @Override public ModelResult send(Consumer<String> progress) { return prepared.run(settings, progress); }
            @Override public void accept(ModelResult result) { prepared.accept((ChatGptResponsesClient.Result) result); }
            @Override public void close() { prepared.close(); }
        };
    }
}
