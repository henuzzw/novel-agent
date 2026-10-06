package com.novelagent.planning.application;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.api.GenerateStoryBibleRequest;
import com.novelagent.planning.api.StoryBibleResponse;
import com.novelagent.planning.api.StoryBibleVersionSummaryResponse;
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
import java.util.List;
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
    private final PlanningMaterialSyncService materials;

    public StoryBibleService(NovelProjectRepository projectRepository, StoryDirectionSetRepository directionRepository,
            StoryBibleVersionRepository bibleRepository, StoryBibleGenerationWorkflow workflow,
            CurrentActorProvider actorProvider, CharacterNameService characterNames, PlanningMaterialSyncService materials) {
        this.projectRepository = projectRepository;
        this.directionRepository = directionRepository;
        this.bibleRepository = bibleRepository;
        this.workflow = workflow;
        this.actorProvider = actorProvider;
        this.characterNames = characterNames;
        this.materials = materials;
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
        if (request.mode() == GenerationMode.REGENERATE && request.baseBibleVersionId() != null) {
            throw new IllegalArgumentException("重新生成时不能指定基准故事圣经版本");
        }
        StoryBibleVersion base = request.mode() == GenerationMode.REGENERATE ? null
                : request.baseBibleVersionId() == null ? previous.orElse(null)
                : requireVersion(projectId, request.baseBibleVersionId());
        StoryBibleContent previousContent = base == null ? null : base.getContent();
        GeneratedStoryBible generated = workflow.generate(projectId, source.getInputSnapshot(), direction,
                provider, previousContent, normalize(request.instruction()));
        int generation = previous
                .map(item -> item.getGenerationNumber() + 1).orElse(1);
        StoryBibleVersion version = StoryBibleVersion.create(UUID.randomUUID(), projectId, generation,
                generated.generatorType(), normalize(request.instruction()), source.getId(), direction.id(),
                base == null ? null : base.getId(),
                generated.content(), generated.changeSummary());
        return response(bibleRepository.saveAndFlush(version));
    }

    @Transactional(readOnly = true)
    public Optional<StoryBibleResponse> latest(UUID projectId) {
        requireOwnedProject(projectId);
        return bibleRepository.findFirstByProjectIdOrderByGenerationNumberDesc(projectId).map(this::response);
    }

    @Transactional(readOnly = true)
    public Optional<StoryBibleResponse> current(UUID projectId) {
        var project = requireOwnedProject(projectId);
        if (project.getCurrentBibleVersionId() == null) return Optional.empty();
        var bible = requireVersion(projectId, project.getCurrentBibleVersionId());
        if (bible.getStatus() != StoryBibleStatus.PUBLISHED) {
            throw new IllegalArgumentException("当前故事圣经尚未发布");
        }
        return Optional.of(response(bible));
    }

    @Transactional(readOnly = true)
    public List<StoryBibleVersionSummaryResponse> versions(UUID projectId) {
        requireOwnedProject(projectId);
        return bibleRepository.findAllByProjectIdOrderByGenerationNumberDesc(projectId).stream()
                .map(value -> StoryBibleVersionSummaryResponse.from(value,
                        characterNames.render(projectId, value.getContent().logline())))
                .toList();
    }

    @Transactional(readOnly = true)
    public StoryBibleResponse version(UUID projectId, UUID versionId) {
        requireOwnedProject(projectId);
        return response(requireVersion(projectId, versionId));
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
    public StoryBibleResponse createRevision(UUID projectId, UUID versionId, long expectedVersion,
            StoryBibleContent content) {
        requireOwnedProject(projectId);
        StoryBibleVersion source = requireVersion(projectId, versionId);
        checkVersion(source, expectedVersion);
        if (source.getStatus() != StoryBibleStatus.PUBLISHED) {
            throw new IllegalArgumentException("只有已发布的故事圣经才能创建修订草稿");
        }
        int generation = bibleRepository.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)
                .map(value -> value.getGenerationNumber() + 1).orElse(1);
        StoryBibleVersion revision = source.getSourceImportId() == null
                ? StoryBibleVersion.create(UUID.randomUUID(), projectId, generation, "AUTHOR_EDIT", null,
                        source.getSourceDirectionSetId(), source.getSourceCandidateId(), source.getId(), content, List.of())
                : StoryBibleVersion.createFromImport(UUID.randomUUID(), projectId, generation, "AUTHOR_EDIT", null,
                        source.getSourceImportId(), source.getId(), content);
        return response(bibleRepository.saveAndFlush(revision));
    }

    @Transactional
    public StoryBibleResponse publish(UUID projectId, UUID versionId, long expectedVersion) {
        NovelProject project = requireOwnedProject(projectId);
        StoryBibleVersion version = requireVersion(projectId, versionId);
        checkVersion(version, expectedVersion);
        version.publish();
        project.publishStoryBible(version.getId());
        projectRepository.save(project);
        materials.syncBible(version);
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
