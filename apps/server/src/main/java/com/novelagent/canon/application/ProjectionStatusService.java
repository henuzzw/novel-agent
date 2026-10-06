package com.novelagent.canon.application;

import com.novelagent.canon.api.ProjectionStatus;
import com.novelagent.canon.infrastructure.ProjectionStatusRepository;
import com.novelagent.project.application.ProjectAccessService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProjectionStatusService {
    private final ProjectAccessService access;
    private final ProjectionStatusRepository projections;

    public ProjectionStatusService(ProjectAccessService access, ProjectionStatusRepository projections) {
        this.access = access;
        this.projections = projections;
    }

    public ProjectionStatus status(UUID projectId) {
        long canon = access.requireOwnedProject(projectId).getCurrentCanonVersion();
        return new ProjectionStatus(canon, projections.isPublished(projectId, canon),
                projections.isProjected(projectId, canon, "PGVECTOR"),
                projections.isProjected(projectId, canon, "NEO4J"));
    }
}
