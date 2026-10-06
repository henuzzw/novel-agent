package com.novelagent.project.application;

import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目访问校验。
 *
 * <p>统一确认项目属于当前用户；不存在和无权访问都按不可见处理。校验只是业务访问入口，不负责锁定项目或冻结后续生成依据。</p>
 */
@Service
public class ProjectAccessService {
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actors;

    public ProjectAccessService(NovelProjectRepository projects, CurrentActorProvider actors) {
        this.projects = projects;
        this.actors = actors;
    }

    /**
     * 确认项目属于当前用户，否则以项目不可见处理；本方法不锁定项目或保证后续生成期间来源不变。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional(readOnly = true)
    public NovelProject requireOwnedProject(UUID projectId) {
        return projects.findById(projectId).filter(project -> project.getOwnerId().equals(actors.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }
}
