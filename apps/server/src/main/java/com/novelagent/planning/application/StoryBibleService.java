package com.novelagent.planning.application;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.api.GenerateStoryBibleRequest;
import com.novelagent.planning.api.StoryBibleResponse;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import com.novelagent.planning.domain.StoryDirectionSet;
import com.novelagent.planning.domain.StoryDirectionStatus;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.planning.infrastructure.StoryDirectionSetRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoryBibleService {
    private final NovelProjectRepository projectRepository;
    private final StoryDirectionSetRepository directionRepository;
    private final StoryBibleVersionRepository bibleRepository;
    private final StoryBibleGenerationWorkflow workflow;
    private final CurrentActorProvider actorProvider;
    private final CharacterNameService characterNames;

    public StoryBibleService(NovelProjectRepository projectRepository, StoryDirectionSetRepository directionRepository,
            StoryBibleVersionRepository bibleRepository, StoryBibleGenerationWorkflow workflow,
            CurrentActorProvider actorProvider, CharacterNameService characterNames) {
        this.projectRepository = projectRepository;
        this.directionRepository = directionRepository;
        this.bibleRepository = bibleRepository;
        this.workflow = workflow;
        this.actorProvider = actorProvider;
        this.characterNames = characterNames;
    }

    public StoryBibleResponse generate(UUID projectId, GenerateStoryBibleRequest request) {
        requireOwnedProject(projectId);
        StoryDirectionSet source = directionRepository
                .findFirstByProjectIdAndStatusOrderByGenerationNumberDesc(projectId, StoryDirectionStatus.SELECTED)
                .orElseThrow(() -> new IllegalArgumentException("请先确认一个故事方向，再生成故事圣经"));
        StoryDirectionCandidate direction = source.getDirections().stream()
                .filter(item -> item.id().equals(source.getSelectedCandidateId())).findFirst()
                .orElseThrow(() -> new IllegalStateException("已确认的故事方向不存在"));
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        Optional<StoryBibleVersion> previous = bibleRepository
                .findFirstByProjectIdOrderByGenerationNumberDesc(projectId);
        StoryBibleContent previousContent = request.mode() == GenerationMode.REGENERATE
                ? null
                : previous.map(StoryBibleVersion::getContent).orElse(null);
        GeneratedStoryBible generated = workflow.generate(projectId, source.getInputSnapshot(), direction,
                provider, previousContent, normalize(request.instruction()));
        int generation = previous
                .map(item -> item.getGenerationNumber() + 1).orElse(1);
        StoryBibleVersion version = StoryBibleVersion.create(UUID.randomUUID(), projectId, generation,
                generated.generatorType(), normalize(request.instruction()), source.getId(), direction.id(),
                generated.content(), generated.changeSummary());
        return response(bibleRepository.saveAndFlush(version));
    }

    @Transactional(readOnly = true)
    public Optional<StoryBibleResponse> latest(UUID projectId) {
        requireOwnedProject(projectId);
        return bibleRepository.findFirstByProjectIdOrderByGenerationNumberDesc(projectId).map(this::response);
    }

    @Transactional
    public StoryBibleResponse update(UUID projectId, UUID versionId, long expectedVersion, StoryBibleContent content) {
        requireOwnedProject(projectId);
        StoryBibleVersion version = requireVersion(projectId, versionId);
        checkVersion(version, expectedVersion);
        if (version.getStatus() == StoryBibleStatus.PUBLISHED) {
            throw new IllegalArgumentException("已发布的故事圣经不能直接修改，请生成新版本");
        }
        version.revise(content);
        return response(bibleRepository.saveAndFlush(version));
    }

    @Transactional
    public StoryBibleResponse publish(UUID projectId, UUID versionId, long expectedVersion) {
        NovelProject project = requireOwnedProject(projectId);
        StoryBibleVersion version = requireVersion(projectId, versionId);
        checkVersion(version, expectedVersion);
        version.publish();
        project.publishStoryBible(version.getId());
        projectRepository.save(project);
        return response(bibleRepository.saveAndFlush(version));
    }

    private StoryBibleVersion requireVersion(UUID projectId, UUID id) {
        return bibleRepository.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new StoryBibleVersionNotFoundException(id));
    }

    private StoryBibleResponse response(StoryBibleVersion value) {
        StoryBibleContent content = characterNames.render(value.getProjectId(), value.getContent(), StoryBibleContent.class);
        return StoryBibleResponse.from(value, content);
    }

    private NovelProject requireOwnedProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .filter(project -> project.getOwnerId().equals(actorProvider.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private static void checkVersion(StoryBibleVersion value, long expected) {
        if (value.getRowVersion() != expected) throw new ResourceVersionConflictException(expected, value.getRowVersion());
    }

    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
