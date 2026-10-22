package com.novelagent.agent.application;

import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.infrastructure.AutomationRunRepository;
import com.novelagent.canon.api.dto.PublishManuscriptRequest;
import com.novelagent.canon.application.CanonPublicationService;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.writing.application.DraftLoopContext;
import com.novelagent.writing.domain.DraftJudgment;
import com.novelagent.writing.domain.DraftLoopRun;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.infrastructure.DraftLoopRunRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publication uses the same canonical transaction and source guards as the author's button, under explicit range authorization. */
@Service
public class AutomationDraftPublisher {
    private final ProjectAccessService access;
    private final EntityManager entities;
    private final AutomationRunRepository runs;
    private final DraftLoopRunRepository loops;
    private final DraftLoopContext contexts;
    private final CanonPublicationService publication;
    public AutomationDraftPublisher(ProjectAccessService access, EntityManager entities, AutomationRunRepository runs,
            DraftLoopRunRepository loops, DraftLoopContext contexts, CanonPublicationService publication) {
        this.access = access; this.entities = entities; this.runs = runs; this.loops = loops;
        this.contexts = contexts; this.publication = publication;
    }
    public static boolean eligible(DraftLoopRun loop) {
        if (loop.getStatus() != DraftLoopRun.Status.STOPPED) return false;
        if (loop.getStopReason() == DraftLoopRun.StopReason.B_CLEAR) return true;
        return loop.getStopReason() == DraftLoopRun.StopReason.C_NO_CHANGE && !loop.getRounds().isEmpty()
                && loop.getRounds().getLast().judgment() != null
                && loop.getRounds().getLast().judgment().decisions().stream().allMatch(d -> d.verdict() == DraftJudgment.Verdict.REJECT);
    }
    @Transactional
    public boolean publish(AutomationRun expected, UUID loopId) {
        UUID projectId = expected.getProjectId();
        var project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        var parent = runs.findLocked(projectId, expected.getId()).orElseThrow();
        if (!parent.isAutoPublish() || !parent.ownsLoop(loopId, expected.getAttempt(), expected.getCurrentChapter())) return false;
        var loop = loops.findByIdAndProjectId(loopId, projectId).orElseThrow();
        entities.refresh(loop, LockModeType.PESSIMISTIC_WRITE);
        if (!eligible(loop) || !parent.getId().equals(loop.getAutomationId())
                || loop.getAutomationAttempt() != parent.getAttempt()) return false;
        var source = contexts.capture(projectId, parent.getCurrentChapter());
        var manuscript = source.latest();
        if (manuscript == null || manuscript.getStatus() != ManuscriptStatus.DRAFT
                || !manuscript.getId().equals(loop.getManuscriptId()) || manuscript.getRowVersion() != loop.getManuscriptRowVersion()
                || !source.basis().fingerprint().equals(loop.getBasis().fingerprint())) {
            throw new IllegalStateException("自动发布的正文或创作依据已变化，请由作者核对");
        }
        parent.waitForPublication("自动发布已授权，发布成功后继续下一章");
        runs.saveAndFlush(parent);
        publication.publish(projectId, parent.getCurrentChapter(), new PublishManuscriptRequest(manuscript.getId(),
                manuscript.getRowVersion(), project.getCurrentCanonVersion(), null, parent.getProvider(), parent.isAutoMemory()));
        return true;
    }
}
