package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.infrastructure.CodexMaterialDelta;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Legacy auth and threads remain unchanged; only the outgoing data payload is reduced. */
@Component
public class LegacyCodexPromptGateway {
    private final CodexAppServerClient codex;
    private final CodexSessionManager sessions;
    private final LegacyCodexPromptReducer reducer;
    private final ModelContextProperties context;
    private final Map<UUID, Semaphore> projects = new ConcurrentHashMap<>();
    private final Map<Key, Delivered> delivered = new ConcurrentHashMap<>();

    public LegacyCodexPromptGateway(CodexAppServerClient codex, CodexAgentSessionRepository repository,
            ObjectMapper json, ModelContextProperties context) {
        this.codex = codex; this.sessions = new CodexSessionManager(codex, repository);
        this.reducer = new LegacyCodexPromptReducer(json); this.context = context;
    }

    public Prepared prepare(UUID project, String workflow, String sessionKey, String system, String user,
            CodexSessionPolicy policy, EffectiveSettings settings, String revision) {
        var lock = projects.computeIfAbsent(project, ignored -> new Semaphore(1));
        if (!lock.tryAcquire()) throw new IllegalStateException("该项目 Codex 会话正在生成，请等待或先停止当前任务");
        try {
            var session = sessions.prepare(project, sessionKey, system + LegacyCodexPromptReducer.READ_RULES,
                    policy, settings, revision);
            var key = new Key(project, sessionKey);
            var previous = delivered.get(key);
            Set<String> sent = previous != null && previous.thread().equals(session.getThreadId())
                    ? previous.materials() : Set.of();
            boolean toolAvailable = codex.supportsMaterialTool(session.getThreadId());
            var delta = toolAvailable ? reducer.reduce(workflow, user, sent)
                    : new CodexMaterialDelta.Result(user, Map.of());
            codex.saveMaterialFiles(project, delta.materials());
            if (toolAvailable) codex.openMaterialScope(session.getThreadId(), project, delta.materials().keySet());
            return new Prepared(project, session, key, lock, sent, delta);
        } catch (RuntimeException error) { lock.release(); throw error; }
    }

    public final class Prepared implements AutoCloseable {
        private final UUID project;
        private final CodexAgentSession session;
        private final Key key;
        private final Semaphore lock;
        private final Set<String> previous;
        private final CodexMaterialDelta.Result delta;
        private boolean attempted, accepted, closed;
        Prepared(UUID project, CodexAgentSession session, Key key, Semaphore lock,
                Set<String> previous, CodexMaterialDelta.Result delta) {
            this.project = project; this.session = session; this.key = key; this.lock = lock;
            this.previous = previous; this.delta = delta;
        }
        public String wireInput() { return delta.text(); }
        public String instructions(String system) { return system + LegacyCodexPromptReducer.READ_RULES; }
        public CodexAppServerClient.TurnResult run(String system, EffectiveSettings settings,
                int outputReserve, Consumer<String> progress) {
            return run(system, settings, outputReserve, null, progress);
        }
        public CodexAppServerClient.TurnResult run(String system, EffectiveSettings settings,
                int outputReserve, com.fasterxml.jackson.databind.JsonNode schema, Consumer<String> progress) {
            attempted = true;
            var capacity = context.require(settings.provider());
            long prior = codex.contextTokens(session.getThreadId());
            long incoming = MemoryBudgetAllocator.estimateTokens(system + delta.text() + (schema == null ? "" : schema));
            if (prior > 0 && prior + incoming + outputReserve + capacity.getSafetyMarginTokens()
                    > capacity.getContextWindowTokens()) codex.compactThread(session.getThreadId());
            return codex.runStructuredTurn(session.getThreadId(), project, delta.text(), schema, settings, progress);
        }
        public void accept(CodexAppServerClient.TurnResult result) {
            sessions.recordTurn(session, result.turnId());
            var sent = new LinkedHashSet<>(previous); sent.addAll(delta.materials().keySet());
            delivered.put(key, new Delivered(session.getThreadId(), Set.copyOf(sent)));
            accepted = true;
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { if (attempted && !accepted) delivered.remove(key); }
            finally { codex.closeMaterialScope(session.getThreadId()); lock.release(); }
        }
    }
    private record Key(UUID project, String session) { }
    private record Delivered(String thread, Set<String> materials) { }
}
