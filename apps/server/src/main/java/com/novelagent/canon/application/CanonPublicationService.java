package com.novelagent.canon.application;

import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.agent.infrastructure.AutomationRunRepository;
import com.novelagent.canon.api.CanonCommitResponse;
import com.novelagent.canon.api.PublishManuscriptRequest;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 正文发布用例。在短事务中锁定项目与正文，校验来源版本，原子完成正文确认、正史发布及记忆任务入队。
 */
@Service
public class CanonPublicationService {
    private final ProjectAccessService access;
    private final NovelProjectRepository projects;
    private final ManuscriptVersionRepository manuscripts;
    private final CanonCommitRepository commits;
    private final TypedCanonMaterializer materializer;
    private final CanonPublicationEvents events;
    private final EntityManager entities;
    private final JdbcTemplate jdbc;
    private final AutomationRunRepository automations;

    public CanonPublicationService(
            ProjectAccessService access,
            NovelProjectRepository projects,
            ManuscriptVersionRepository manuscripts,
            CanonCommitRepository commits,
            TypedCanonMaterializer materializer,
            CanonPublicationEvents events,
            EntityManager entities,
            JdbcTemplate jdbc,
            AutomationRunRepository automations) {
        this.access = access;
        this.projects = projects;
        this.manuscripts = manuscripts;
        this.commits = commits;
        this.materializer = materializer;
        this.events = events;
        this.entities = entities;
        this.jdbc = jdbc;
        this.automations = automations;
    }

    /**
     * 发布本章最新正文；校验正文行版本、正史水位与待替换提交，有后续正史时拒绝替换前章。记忆提取在事务提交后异步执行。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapter 目标章节号，从 1 开始
     * @param request 目标正文 ID、正文行版本、正史水位及记忆整理选项
     */
    @Transactional
    public CanonCommitResponse publish(
            UUID projectId, int chapter, PublishManuscriptRequest request) {
        if (chapter < 1
                || request.manuscriptVersionId() == null
                || request.expectedManuscriptVersion() < 0
                || request.expectedCanonVersion() < 0)
            throw new IllegalArgumentException("发布来源或版本无效");
        NovelProject project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        access.requireOwnedProject(project);
        var manuscript =
                manuscripts
                        .findByIdAndProjectIdAndChapterNumber(
                                request.manuscriptVersionId(), projectId, chapter)
                        .orElseThrow(() -> new IllegalArgumentException("本章正文版本不存在"));
        entities.refresh(manuscript, LockModeType.PESSIMISTIC_WRITE);
        var previous =
                commits.findByProjectIdAndChapterNumberAndActiveTrue(projectId, chapter)
                        .orElse(null);
        if (previous != null && previous.getManuscriptVersionId().equals(manuscript.getId())) {
            return CanonCommitResponse.from(previous);
        }
        if (manuscript.getRowVersion() != request.expectedManuscriptVersion()) {
            throw new ResourceVersionConflictException(
                    request.expectedManuscriptVersion(), manuscript.getRowVersion());
        }
        var latest =
                manuscripts
                        .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(
                                projectId, chapter)
                        .orElseThrow();
        if (!latest.getId().equals(manuscript.getId()))
            throw new IllegalStateException("正文已有新版本，请刷新后发布");
        if (previous == null
                ? request.expectedActiveCommitId() != null
                : !previous.getId().equals(request.expectedActiveCommitId())) {
            throw new IllegalStateException("本章发布状态已变化，请刷新后重试");
        }
        if (previous != null
                && commits.existsByProjectIdAndChapterNumberGreaterThanAndActiveTrue(
                        projectId, chapter)) {
            throw new IllegalStateException("后续章节已有正史；请先处理后续章节依赖，暂不能替换本章");
        }
        long canonVersion = project.commitCanon(request.expectedCanonVersion());
        UUID commitId = UUID.randomUUID();
        if (previous != null) {
            previous.supersede(commitId);
            commits.saveAndFlush(previous);
            materializer.retire(previous.getId(), canonVersion);
        }
        manuscript.accept();
        manuscripts.saveAndFlush(manuscript);
        var commit =
                commits.saveAndFlush(
                        new CanonCommit(
                                commitId,
                                projectId,
                                chapter,
                                manuscript.getId(),
                                null,
                                canonVersion,
                                List.of()));
        ModelProvider provider =
                request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        boolean organize =
                request.organizeMemory() != null
                        ? request.organizeMemory()
                        : automations.findTop50ByProjectIdOrderByCreatedAtDesc(projectId).stream()
                                .filter(
                                        run ->
                                                run.getCurrentChapter() == chapter
                                                        && !run.isCancelRequested()
                                                        && (run.getStatus()
                                                                        == AutomationStatus.RUNNING
                                                                || run.isWaitingForPublication()))
                                .findFirst()
                                .map(AutomationRun::isAutoMemory)
                                .orElse(true);
        jdbc.update(
                "INSERT INTO canon_memory_job(commit_id, project_id, provider, status) VALUES (?, ?, ?, ?)",
                commitId,
                projectId,
                provider.name(),
                organize ? "PENDING" : "SKIPPED");
        events.emit(commit);
        projects.saveAndFlush(project);
        return CanonCommitResponse.from(commit);
    }
}
