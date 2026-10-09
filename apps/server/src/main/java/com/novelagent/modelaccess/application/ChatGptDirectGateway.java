package com.novelagent.modelaccess.application;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.infrastructure.ChatGptResponsesClient;
import com.novelagent.modelaccess.infrastructure.HttpConversationStore;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.project.application.GlobalModelSettingsService;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Per-turn instructions plus persisted history. No server-side thread ID or hidden history assumption. */
@Component
public class ChatGptDirectGateway {
    private final ChatGptOAuthService auth;
    private final HttpConversationStore conversations;
    private final ChatGptResponsesClient responses;
    private final GlobalModelSettingsService settings;
    private final ModelContextProperties context;
    public ChatGptDirectGateway(ChatGptOAuthService auth, HttpConversationStore conversations, ChatGptResponsesClient responses,
            GlobalModelSettingsService settings, ModelContextProperties context) {
        this.auth = auth; this.conversations = conversations; this.responses = responses;
        this.settings = settings; this.context = context;
    }
    public EffectiveSettings effectiveSettings() {
        var value = settings.get();
        return new EffectiveSettings(ModelProvider.LOCAL_CODEX, value.codexModel(), value.codexEffort(), value.version());
    }
    public Prepared prepare(UUID project, String workflow, String instructions, String user, CodexSessionPolicy policy,
            String revision, int outputReserve) {
        String binding = ChatGptResponsesClient.accountBinding(auth.authorization());
        var lease = conversations.claim(project, workflow, binding, revision, policy == CodexSessionPolicy.NEW_THREAD);
        try {
            ArrayNode input = lease.history().deepCopy();
            input.addObject().put("role", "user").put("content", user);
            var capacity = context.require(ModelProvider.LOCAL_CODEX);
            long tokens = MemoryBudgetAllocator.estimateTokens(instructions + "\n" + input);
            if (tokens + outputReserve + capacity.getSafetyMarginTokens() > capacity.getContextWindowTokens())
                throw new IllegalArgumentException("完整会话历史超过上下文容量；请缩小资料或明确开启新会话，系统不会静默丢弃历史");
            var budget = new AgentRunRecorder.ContextBudget(capacity.getContextWindowTokens(), capacity.getSafetyMarginTokens(), tokens, outputReserve);
            return new Prepared(lease, binding, instructions, input, budget);
        } catch (RuntimeException error) { conversations.release(lease); throw error; }
    }
    public final class Prepared implements AutoCloseable {
        private final HttpConversationStore.Lease lease;
        private final String binding, instructions;
        private final ArrayNode input;
        private final AgentRunRecorder.ContextBudget budget;
        private boolean completed;
        Prepared(HttpConversationStore.Lease lease, String binding, String instructions, ArrayNode input, AgentRunRecorder.ContextBudget budget) {
            this.lease = lease; this.binding = binding; this.instructions = instructions; this.input = input; this.budget = budget;
        }
        public String wireInput() { return input.toString(); }
        public AgentRunRecorder.ContextBudget budget() { return budget; }
        public ChatGptResponsesClient.Result run(EffectiveSettings selected, Consumer<String> progress) {
            var renewed = new AtomicLong(System.nanoTime());
            return responses.request(instructions, input, selected, binding, progress, () -> {
                if (System.nanoTime() - renewed.get() > java.time.Duration.ofMinutes(1).toNanos()) {
                    conversations.renew(lease); renewed.set(System.nanoTime());
                }
            });
        }
        public void accept(ChatGptResponsesClient.Result result) {
            ArrayNode history = input.deepCopy();
            for (var item : result.historyOutput()) history.add(item.deepCopy());
            conversations.complete(lease, history); completed = true;
        }
        @Override public void close() { if (!completed) conversations.release(lease); }
    }
}
