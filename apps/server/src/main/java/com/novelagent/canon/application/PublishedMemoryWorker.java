package com.novelagent.canon.application;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;

/** Single-process durable queue; failures require explicit retry, including after restart. */
@Component
public class PublishedMemoryWorker {
    private final PublishedMemoryService memory;
    private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean ready;
    public PublishedMemoryWorker(PublishedMemoryService memory) {
        this.memory = memory;
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("published-memory-"); executor.setDaemon(true); executor.initialize();
    }
    @EventListener(ApplicationReadyEvent.class)
    public void ready() { memory.recoverInterrupted(); ready = true; }
    @Scheduled(fixedDelayString = "${novel.canon.memory-poll-ms:2000}")
    public void poll() {
        if (!ready || !busy.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try { var source = memory.claim(); if (source != null) memory.process(source); }
                finally { busy.set(false); }
            });
        } catch (RuntimeException failure) { busy.set(false); throw failure; }
    }
    @PreDestroy
    public void shutdown() { ready = false; executor.shutdown(); }
}
