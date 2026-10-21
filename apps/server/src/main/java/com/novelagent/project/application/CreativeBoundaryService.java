package com.novelagent.project.application;

import static com.novelagent.project.application.ResourceVersionConflictException.checkVersion;

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

/**
 * 管理项目世界设定与改编权限。短事务内保存选择及原文评估，向模型提供边界文字，不重写既有规划。
 */
@Service
public class CreativeBoundaryService {
    private final ProjectAccessService access;
    private final NovelProjectRepository projects;
    private final EntityManager entities;

    public CreativeBoundaryService(
            ProjectAccessService access, NovelProjectRepository projects, EntityManager entities) {
        this.access = access;
        this.projects = projects;
        this.entities = entities;
    }

    public record State(
            CreativeBoundaries.WorldMode worldMode,
            CreativeBoundaries.AdaptationScope adaptationScope,
            CreativeBoundaries.WorldMode effectiveWorld,
            CreativeBoundaries.AdaptationScope effectiveScope,
            boolean assessed,
            String reason,
            String sourceImportId,
            long version) {}

    /**
     * 校验项目归属并返回作者选择、有效边界、来源评估及项目行版本。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @Transactional(readOnly = true)
    public State get(UUID projectId) {
        return state(access.requireOwnedProject(projectId));
    }

    /**
     * 锁定项目并核对行版本后更新世界模式和改编范围；按请求决定是否清除来源评估。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param world 世界设定选择
     * @param scope 改编范围选择
     * @param version 预期行版本，用于拒绝并发覆盖
     * @param reset 是否显式清除先前保存的来源评估
     */
    @Transactional
    public State update(
            UUID projectId,
            CreativeBoundaries.WorldMode world,
            CreativeBoundaries.AdaptationScope scope,
            long version,
            boolean reset) {
        var project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        checkVersion(version, project.getRowVersion());
        if (world == null || scope == null) throw new IllegalArgumentException("请选择世界设定和改编幅度");
        CreativeBoundaries.from(project).choose(world, scope, reset).applyTo(project);
        projects.saveAndFlush(project);
        return state(project);
    }

    /**
     * 将当前项目有效创作边界渲染为模型上下文，不更改项目或历史产物。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @Transactional(readOnly = true)
    public String promptContext(UUID projectId) {
        return CreativeBoundaryGuide.render(
                CreativeBoundaries.from(access.requireOwnedProject(projectId)));
    }

    /** 仅在全部原文分段通过逐字证据与来源校验后写入评估；相同来源复用已有结果。 */
    @Transactional
    public void assess(
            UUID projectId,
            UUID importId,
            String sourceHash,
            List<ImportAnalysis.WorldAssessment> assessments) {
        var project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        var current = CreativeBoundaries.from(project);
        // Retrying the same source reuses the saved assessment; a changed source is evaluated once
        // again.
        if (sourceHash.equals(current.sourceHash())
                && importId.toString().equals(current.sourceImportId())) return;
        var world = CreativeBoundaries.WorldMode.REALISTIC;
        String reason = "原文没有提供足够的架空或虚构活动依据，采用现实写实；核心主线保持不变。";
        for (var assessment : assessments) {
            if (assessment.worldMode() == CreativeBoundaries.WorldMode.ALTERNATE
                    || (world != CreativeBoundaries.WorldMode.ALTERNATE
                            && assessment.worldMode()
                                    == CreativeBoundaries.WorldMode.REALISTIC_FICTION)) {
                world = assessment.worldMode();
                reason = assessment.reason();
            } else if (world == CreativeBoundaries.WorldMode.REALISTIC)
                reason = assessment.reason();
        }
        current.assess(world, reason, sourceHash, importId.toString()).applyTo(project);
        projects.saveAndFlush(project);
    }

    private static State state(NovelProject project) {
        var p = CreativeBoundaries.from(project);
        return new State(
                p.worldMode(),
                p.adaptationScope(),
                p.effectiveWorld(),
                p.effectiveScope(),
                p.assessedWorld() != null,
                p.reason(),
                p.sourceImportId(),
                project.getRowVersion());
    }
}
