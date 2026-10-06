package com.novelagent.canon.application;

import com.novelagent.canon.api.ProjectionStatus;
import com.novelagent.canon.infrastructure.ProjectionStatusRepository;
import com.novelagent.project.application.ProjectAccessService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 正史投影状态。
 *
 * <p>校验项目归属后，按项目当前正史版本组合 Outbox、Neo4j、向量投影进度。查询不推进投影，也不修改权威提交状态。</p>
 */
@Service
@Transactional(readOnly = true)
public class ProjectionStatusService {
    private final ProjectAccessService access;
    private final ProjectionStatusRepository projections;

    public ProjectionStatusService(ProjectAccessService access, ProjectionStatusRepository projections) {
        this.access = access;
        this.projections = projections;
    }

    /**
     * 查询现有业务状态与可得进度，不执行生成、提交或投影。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    public ProjectionStatus status(UUID projectId) {
        long canon = access.requireOwnedProject(projectId).getCurrentCanonVersion();
        return new ProjectionStatus(canon, projections.isPublished(projectId, canon),
                projections.isProjected(projectId, canon, "PGVECTOR"),
                projections.isProjected(projectId, canon, "NEO4J"));
    }
}
