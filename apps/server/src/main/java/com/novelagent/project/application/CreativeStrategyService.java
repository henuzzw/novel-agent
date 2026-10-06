package com.novelagent.project.application;

import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreativeStrategyService {
    private final ProjectAccessService access;
    private final NovelProjectRepository projects;
    private final EntityManager entities;

    public CreativeStrategyService(ProjectAccessService access, NovelProjectRepository projects, EntityManager entities) {
        this.access = access;
        this.projects = projects;
        this.entities = entities;
    }

    public record State(CreativeStrategy strategy, int policyVersion, long version) {}

    @Transactional(readOnly = true)
    public State get(UUID projectId) {
        return state(access.requireOwnedProject(projectId));
    }

    @Transactional
    public State update(UUID projectId, CreativeStrategy strategy, long expectedVersion) {
        NovelProject project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        if (project.getRowVersion() != expectedVersion) {
            throw new ResourceVersionConflictException(expectedVersion, project.getRowVersion());
        }
        if (strategy == null) {
            throw new IllegalArgumentException("请选择创作策略");
        }
        CreativeStrategyPolicy.of(strategy).applyTo(project);
        projects.saveAndFlush(project);
        return state(project);
    }

    @Transactional(readOnly = true)
    public String promptContext(UUID projectId) {
        return CreativeStrategyGuide.render(CreativeStrategyPolicy.from(access.requireOwnedProject(projectId)));
    }

    private static State state(NovelProject project) {
        var policy = CreativeStrategyPolicy.from(project);
        return new State(policy.strategy(), policy.policyVersion(), project.getRowVersion());
    }
}
