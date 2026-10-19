package com.novelagent.project.application;

import com.novelagent.ingest.domain.ImportAnalysis;
import com.novelagent.project.domain.CreativeBoundaries;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Saves project-level choices without invoking a model or rewriting saved planning. */
@Service
public class CreativeBoundaryService {
    private final ProjectAccessService access;
    private final NovelProjectRepository projects;
    private final EntityManager entities;
    public CreativeBoundaryService(ProjectAccessService access, NovelProjectRepository projects, EntityManager entities) {
        this.access = access; this.projects = projects; this.entities = entities;
    }
    public record State(CreativeBoundaries.WorldMode worldMode, CreativeBoundaries.AdaptationScope adaptationScope,
            CreativeBoundaries.WorldMode effectiveWorld, CreativeBoundaries.AdaptationScope effectiveScope,
            boolean assessed, String reason, String sourceImportId, long version) { }

    @Transactional(readOnly = true)
    public State get(UUID projectId) { return state(access.requireOwnedProject(projectId)); }

    @Transactional
    public State update(UUID projectId, CreativeBoundaries.WorldMode world, CreativeBoundaries.AdaptationScope scope,
            long version, boolean reset) {
        var project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        if (project.getRowVersion() != version) throw new ResourceVersionConflictException(version, project.getRowVersion());
        if (world == null || scope == null) throw new IllegalArgumentException("请选择世界设定和改编幅度");
        CreativeBoundaries.from(project).choose(world, scope, reset).applyTo(project);
        projects.saveAndFlush(project);
        return state(project);
    }

    @Transactional(readOnly = true)
    public String promptContext(UUID projectId) {
        return CreativeBoundaryGuide.render(CreativeBoundaries.from(access.requireOwnedProject(projectId)));
    }

    /** Called only after every source slice has passed literal evidence and freshness checks. */
    @Transactional
    public void assess(UUID projectId, UUID importId, String sourceHash,
            List<ImportAnalysis.WorldAssessment> assessments) {
        var project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        var current = CreativeBoundaries.from(project);
        // Retrying the same source reuses the saved assessment; a changed source is evaluated once again.
        if (sourceHash.equals(current.sourceHash()) && importId.toString().equals(current.sourceImportId())) return;
        var world = CreativeBoundaries.WorldMode.REALISTIC;
        String reason = "原文没有提供足够的架空或虚构活动依据，采用现实写实；核心主线保持不变。";
        for (var assessment : assessments) {
            if (assessment.worldMode() == CreativeBoundaries.WorldMode.ALTERNATE
                    || (world != CreativeBoundaries.WorldMode.ALTERNATE
                    && assessment.worldMode() == CreativeBoundaries.WorldMode.REALISTIC_FICTION)) {
                world = assessment.worldMode(); reason = assessment.reason();
            } else if (world == CreativeBoundaries.WorldMode.REALISTIC) reason = assessment.reason();
        }
        current.assess(world, reason, sourceHash, importId.toString()).applyTo(project);
        projects.saveAndFlush(project);
    }

    private static State state(NovelProject project) {
        var p = CreativeBoundaries.from(project);
        return new State(p.worldMode(), p.adaptationScope(), p.effectiveWorld(), p.effectiveScope(),
                p.assessedWorld() != null, p.reason(), p.sourceImportId(), project.getRowVersion());
    }
}
