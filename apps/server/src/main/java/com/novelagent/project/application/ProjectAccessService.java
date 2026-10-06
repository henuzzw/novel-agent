package com.novelagent.project.application;

import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectAccessService {
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actors;

    public ProjectAccessService(NovelProjectRepository projects, CurrentActorProvider actors) {
        this.projects = projects;
        this.actors = actors;
    }

    @Transactional(readOnly = true)
    public NovelProject requireOwnedProject(UUID projectId) {
        return projects.findById(projectId).filter(project -> project.getOwnerId().equals(actors.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }
}
