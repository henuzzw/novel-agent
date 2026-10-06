package com.novelagent.project.application;

import com.novelagent.project.api.CreateProjectRequest;
import com.novelagent.project.api.CreativeIntentRequest;
import com.novelagent.project.api.ProjectResponse;
import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final NovelProjectRepository projectRepository;
    private final CreativeIntentRepository creativeIntentRepository;
    private final CurrentActorProvider actorProvider;

    public ProjectService(
            NovelProjectRepository projectRepository,
            CreativeIntentRepository creativeIntentRepository,
            CurrentActorProvider actorProvider) {
        this.projectRepository = projectRepository;
        this.creativeIntentRepository = creativeIntentRepository;
        this.actorProvider = actorProvider;
    }

    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        validateIdeaRequest(request.entryMode(), request.creativeIntent());

        UUID projectId = UUID.randomUUID();
        NovelProject project = NovelProject.create(
                projectId,
                actorProvider.currentUserId(),
                request.name(),
                request.entryMode());
        CreativeStrategyPolicy.of(request.creativeStrategy()).applyTo(project);
        projectRepository.saveAndFlush(project);

        CreativeIntent intent = null;
        if (request.creativeIntent() != null) {
            intent = new CreativeIntent(projectId);
            apply(intent, request.creativeIntent());
            creativeIntentRepository.saveAndFlush(intent);
        }

        return ProjectResponse.from(project, intent);
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        UUID ownerId = actorProvider.currentUserId();
        return projectRepository.findAllByOwnerIdOrderByUpdatedAtDesc(ownerId).stream()
                .map(project -> ProjectResponse.from(
                        project,
                        creativeIntentRepository.findById(project.getId()).orElse(null)))
                .toList();
    }

    @Transactional(readOnly = true)
    public ProjectResponse get(UUID projectId) {
        NovelProject project = requireOwnedProject(projectId);
        CreativeIntent intent = creativeIntentRepository.findById(projectId).orElse(null);
        return ProjectResponse.from(project, intent);
    }

    @Transactional
    public ProjectResponse updateCreativeIntent(UUID projectId, long expectedVersion, CreativeIntentRequest request) {
        NovelProject project = requireOwnedProject(projectId);
        CreativeIntent intent = creativeIntentRepository.findById(projectId)
                .orElseGet(() -> new CreativeIntent(projectId));

        if (creativeIntentRepository.existsById(projectId) && intent.getRowVersion() != expectedVersion) {
            throw new ResourceVersionConflictException(expectedVersion, intent.getRowVersion());
        }

        apply(intent, request);
        creativeIntentRepository.saveAndFlush(intent);
        return ProjectResponse.from(project, intent);
    }

    private NovelProject requireOwnedProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .filter(project -> project.getOwnerId().equals(actorProvider.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private static void validateIdeaRequest(EntryMode entryMode, CreativeIntentRequest intent) {
        if (entryMode == EntryMode.IDEA && intent == null) {
            throw new IllegalArgumentException("Creative intent is required for IDEA entry mode");
        }
    }

    private static void apply(CreativeIntent intent, CreativeIntentRequest request) {
        intent.update(
                request.premise(),
                request.genres(),
                request.targetAudience(),
                request.protagonistBrief(),
                request.centralConflict(),
                request.tones(),
                request.targetWords(),
                request.endingPreference(),
                request.mustHave(),
                request.avoid(),
                request.stylePreferences());
    }
}
