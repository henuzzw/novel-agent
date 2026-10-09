package com.novelagent.canon.application;

import com.novelagent.canon.api.CanonCommitResponse;
import com.novelagent.canon.api.PublishManuscriptRequest;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.ResourceVersionConflictException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Author confirmation, publication and durable memory enqueue share one short transaction. */
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

    public CanonPublicationService(ProjectAccessService access, NovelProjectRepository projects,
            ManuscriptVersionRepository manuscripts, CanonCommitRepository commits,
            TypedCanonMaterializer materializer, CanonPublicationEvents events, EntityManager entities,
            JdbcTemplate jdbc) {
        this.access = access; this.projects = projects; this.manuscripts = manuscripts;
        this.commits = commits; this.materializer = materializer; this.events = events;
        this.entities = entities; this.jdbc = jdbc;
    }

    @Transactional
    public CanonCommitResponse publish(UUID projectId, int chapter, PublishManuscriptRequest request) {
        if (chapter < 1 || request.manuscriptVersionId() == null || request.expectedManuscriptVersion() < 0
                || request.expectedCanonVersion() < 0) throw new IllegalArgumentException("发布来源或版本无效");
        NovelProject project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        access.requireOwnedProject(project);
        var manuscript = manuscripts.findByIdAndProjectIdAndChapterNumber(request.manuscriptVersionId(), projectId, chapter)
                .orElseThrow(() -> new IllegalArgumentException("本章正文版本不存在"));
        entities.refresh(manuscript, LockModeType.PESSIMISTIC_WRITE);
        var previous = commits.findByProjectIdAndChapterNumberAndActiveTrue(projectId, chapter).orElse(null);
        if (previous != null && previous.getManuscriptVersionId().equals(manuscript.getId())) {
            return CanonCommitResponse.from(previous);
        }
        if (manuscript.getRowVersion() != request.expectedManuscriptVersion()) {
            throw new ResourceVersionConflictException(request.expectedManuscriptVersion(), manuscript.getRowVersion());
        }
        var latest = manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapter).orElseThrow();
        if (!latest.getId().equals(manuscript.getId())) throw new IllegalStateException("正文已有新版本，请刷新后发布");
        if (previous == null ? request.expectedActiveCommitId() != null
                : !previous.getId().equals(request.expectedActiveCommitId())) {
            throw new IllegalStateException("本章发布状态已变化，请刷新后重试");
        }
        if (previous != null && commits.existsByProjectIdAndChapterNumberGreaterThanAndActiveTrue(projectId, chapter)) {
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
        var commit = commits.saveAndFlush(new CanonCommit(commitId, projectId, chapter, manuscript.getId(), null,
                canonVersion, List.of()));
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        jdbc.update("INSERT INTO canon_memory_job(commit_id, project_id, provider) VALUES (?, ?, ?)",
                commitId, projectId, provider.name());
        events.emit(commit);
        projects.saveAndFlush(project);
        return CanonCommitResponse.from(commit);
    }
}
