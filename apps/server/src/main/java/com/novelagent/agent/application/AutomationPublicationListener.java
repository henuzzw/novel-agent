package com.novelagent.agent.application;

import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.agent.infrastructure.AutomationRunRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/** Only committed publication advances an armed range. Polling covers missed notifications, not failed model retries. */
@Component
public class AutomationPublicationListener {
    public record Published(UUID projectId, int chapter) { }
    private static final Logger log = LoggerFactory.getLogger(AutomationPublicationListener.class);
    private final AutomationService service;
    private final AutomationRunRepository runs;
    public AutomationPublicationListener(AutomationService service, AutomationRunRepository runs) {
        this.service = service; this.runs = runs;
    }
    @TransactionalEventListener
    public void published(Published event) {
        runs.findTop50ByProjectIdOrderByCreatedAtDesc(event.projectId()).stream()
                .filter(run -> run.isWaitingForPublication() && run.getCurrentChapter() == event.chapter())
                .forEach(run -> resume(run.getProjectId(), run.getId(), event.chapter()));
    }
    @Scheduled(fixedDelay = 30000, initialDelay = 30000)
    public void reconcilePublished() {
        runs.findTop50ByWaitingForPublicationTrueAndStatusOrderByUpdatedAtAsc(AutomationStatus.WAITING_FOR_USER)
                .forEach(run -> resume(run.getProjectId(), run.getId(), run.getCurrentChapter()));
    }
    private void resume(UUID projectId, UUID runId, int chapter) {
        try { service.published(projectId, runId, chapter); }
        catch (RuntimeException failure) {
            log.warn("Chapter publication continuation deferred projectId={} runId={} exceptionType={}", projectId, runId, failure.getClass().getSimpleName());
        }
    }
}
