package com.novelagent.modelaccess.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.infrastructure.CodexConversationStore;
import com.novelagent.modelaccess.infrastructure.CodexMaterialDelta;
import com.novelagent.modelaccess.infrastructure.OAuthCodexClient;
import com.novelagent.planning.infrastructure.CodexAppServerClient;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;

/** Codex owns history and compaction; this adapter owns authoritative inputs and save acceptance. */
@Component
public class CodexProjectGateway {
    private final OAuthCodexClient codex;
    private final CodexConversationStore conversations;
    private final ModelContextProperties context;
    private final Semaphore runtime = new Semaphore(1);
    public CodexProjectGateway(OAuthCodexClient codex, CodexConversationStore conversations, ModelContextProperties context) {
        this.codex = codex; this.conversations = conversations; this.context = context;
    }
    public Prepared prepare(UUID project, String workflow, boolean fresh, String user) {
        if (!runtime.tryAcquire()) throw new IllegalStateException("Codex 项目会话入口正在执行任务，请结束后重试");
        CodexConversationStore.Lease lease = null;
        try {
            String binding = codex.authorize();
            lease = conversations.claim(project, workflow, binding, fresh);
            var delta = CodexMaterialDelta.build(user, lease.sent());
            conversations.saveMaterials(lease, delta.materials());
            return new Prepared(lease, binding, delta);
        } catch (RuntimeException error) {
            try { if (lease != null) conversations.release(lease); } finally { runtime.release(); }
            throw error;
        }
    }
    public final class Prepared implements AutoCloseable {
        private final CodexConversationStore.Lease lease;
        private final String binding;
        private final CodexMaterialDelta.Result delta;
        private String thread;
        private boolean accepted;
        private boolean attempted;
        Prepared(CodexConversationStore.Lease lease, String binding, CodexMaterialDelta.Result delta) {
            this.lease = lease; this.binding = binding; this.delta = delta;
        }
        public String wireInput() { return delta.text(); }
        public String instructions(String current) { return current + CodexMaterialDelta.READ_RULES; }
        public CodexAppServerClient.TurnResult run(String system, EffectiveSettings choice, int outputReserve, Consumer<String> progress) {
            return run(system, choice, outputReserve, null, progress);
        }
        public CodexAppServerClient.TurnResult run(String system, EffectiveSettings choice, int outputReserve,
                JsonNode schema, Consumer<String> progress) {
            attempted = true;
            var timer = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().factory());
            var lostLease = new AtomicBoolean();
            Thread owner = Thread.currentThread();
            var heartbeat = timer.scheduleAtFixedRate(() -> {
                try { conversations.renew(lease); }
                catch (RuntimeException error) { lostLease.set(true); owner.interrupt(); }
            }, 1, 1, TimeUnit.MINUTES);
            try {
                thread = lease.thread();
                if (thread == null) {
                    thread = codex.startThread(lease.project(), system, choice);
                    conversations.bind(lease, thread);
                } else codex.resumeThread(thread, lease.project(), system, choice);
                codex.openScope(thread, lease, delta.materials().keySet(), binding);
                var capacity = context.require(choice.provider());
                long previous = Math.max(lease.contextTokens(), codex.contextTokens(thread));
                long incoming = MemoryBudgetAllocator.estimateTokens(system + delta.text() + (schema == null ? "" : schema));
                if (previous > 0 && previous + incoming + outputReserve + capacity.getSafetyMarginTokens()
                        > capacity.getContextWindowTokens()) codex.compactThread(thread);
                return codex.runStructuredTurn(thread, lease.project(), delta.text(), schema, choice, progress);
            } finally {
                heartbeat.cancel(false); timer.shutdownNow();
                if (thread != null) codex.closeScope(thread);
                if (lostLease.get()) Thread.currentThread().interrupt();
            }
        }
        public void accept() {
            var sent = new LinkedHashSet<>(lease.sent()); sent.addAll(delta.materials().keySet());
            conversations.complete(lease, sent, codex.contextTokens(thread));
            accepted = true;
        }
        private boolean closed;
        @Override public void close() {
            if (closed) return;
            closed = true;
            try { if (!accepted) { if (attempted) conversations.abort(lease); else conversations.release(lease); } }
            finally { runtime.release(); }
        }
    }
}
