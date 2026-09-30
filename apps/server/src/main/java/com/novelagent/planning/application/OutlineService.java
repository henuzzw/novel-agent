package com.novelagent.planning.application;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.api.GenerateOutlineRequest;
import com.novelagent.planning.api.OutlineResponse;
import com.novelagent.planning.api.OutlineVersionSummaryResponse;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.OutlineWordBudgetPolicy;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutlineService {
    private final NovelProjectRepository projects;
    private final CreativeIntentRepository intents;
    private final StoryBibleVersionRepository bibles;
    private final OutlineVersionRepository outlines;
    private final OutlineWordBudgetPolicy budgetPolicy;
    private final OutlineGenerationWorkflow workflow;
    private final CurrentActorProvider actorProvider;
    private final CharacterNameService characterNames;

    public OutlineService(NovelProjectRepository projects, CreativeIntentRepository intents,
            StoryBibleVersionRepository bibles, OutlineVersionRepository outlines,
            OutlineWordBudgetPolicy budgetPolicy, OutlineGenerationWorkflow workflow,
            CurrentActorProvider actorProvider, CharacterNameService characterNames) {
        this.projects = projects; this.intents = intents; this.bibles = bibles; this.outlines = outlines;
        this.budgetPolicy = budgetPolicy; this.workflow = workflow; this.actorProvider = actorProvider;
        this.characterNames = characterNames;
    }

    public OutlineResponse generate(UUID projectId, GenerateOutlineRequest request) {
        NovelProject project = requireOwnedProject(projectId);
        UUID bibleId = project.getCurrentBibleVersionId();
        if (bibleId == null) throw new IllegalArgumentException("请先发布故事圣经，再生成分层大纲");
        StoryBibleVersion bible = bibles.findByIdAndProjectId(bibleId, projectId)
                .filter(value -> value.getStatus() == StoryBibleStatus.PUBLISHED)
                .orElseThrow(() -> new IllegalArgumentException("项目当前故事圣经不可用，请重新发布"));
        CreativeIntent intent = intents.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目缺少创作意图"));
        OutlineWordBudget budget = budgetPolicy.plan(intent.getTargetWords());
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        Optional<OutlineVersion> latest = outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId);
        if (request.mode() == GenerationMode.REGENERATE && request.baseOutlineVersionId() != null) {
            throw new IllegalArgumentException("重新生成时不能指定基准大纲版本");
        }
        OutlineVersion base = request.mode() == GenerationMode.REGENERATE ? null
                : request.baseOutlineVersionId() == null ? latest.orElse(null)
                : requireVersion(projectId, request.baseOutlineVersionId());
        StoryBibleContent promptBible = characterNames.render(projectId, bible.getContent(), StoryBibleContent.class);
        OutlineContent previousContent = base == null ? null
                : characterNames.render(projectId, base.getContent(), OutlineContent.class);
        GeneratedOutline generated = workflow.generate(projectId, promptBible, budget, provider,
                previousContent, normalize(request.instruction()));
        int generation = latest
                .map(value -> value.getGenerationNumber() + 1).orElse(1);
        OutlineVersion version = OutlineVersion.create(UUID.randomUUID(), projectId, generation,
                generated.generatorType(), normalize(request.instruction()), bibleId,
                base == null ? null : base.getId(), budget, generated.content(), generated.changeSummary());
        return response(outlines.saveAndFlush(version));
    }

    @Transactional(readOnly = true)
    public List<OutlineVersionSummaryResponse> versions(UUID projectId) {
        requireOwnedProject(projectId);
        return outlines.findAllByProjectIdOrderByGenerationNumberDesc(projectId).stream()
                .map(value -> OutlineVersionSummaryResponse.from(value,
                        characterNames.render(projectId, value.getContent().title())))
                .toList();
    }

    @Transactional(readOnly = true)
    public OutlineResponse version(UUID projectId, UUID outlineId) {
        requireOwnedProject(projectId);
        return response(requireVersion(projectId, outlineId));
    }

    @Transactional(readOnly = true)
    public Optional<OutlineResponse> latest(UUID projectId) {
        requireOwnedProject(projectId);
        return outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId).map(this::response);
    }

    @Transactional(readOnly = true)
    public Optional<OutlineResponse> current(UUID projectId) {
        NovelProject project = requireOwnedProject(projectId);
        UUID currentId = project.getCurrentOutlineVersionId();
        if (currentId == null) return Optional.empty();
        return Optional.of(response(requireVersion(projectId, currentId)));
    }

    @Transactional
    public OutlineResponse update(UUID projectId, UUID outlineId, long expectedVersion, OutlineContent content) {
        requireOwnedProject(projectId);
        OutlineVersion version = requireVersion(projectId, outlineId);
        checkVersion(version, expectedVersion);
        if (version.getStatus() == OutlineStatus.PUBLISHED)
            throw new IllegalArgumentException("已发布的大纲不能直接修改，请生成新版本");
        version.revise(content);
        return response(outlines.saveAndFlush(version));
    }

    @Transactional
    public OutlineResponse publish(UUID projectId, UUID outlineId, long expectedVersion) {
        NovelProject project = requireOwnedProject(projectId);
        OutlineVersion version = requireVersion(projectId, outlineId);
        checkVersion(version, expectedVersion);
        version.publish();
        project.publishOutline(version.getId());
        projects.save(project);
        return response(outlines.saveAndFlush(version));
    }

    private OutlineVersion requireVersion(UUID projectId, UUID id) {
        return outlines.findByIdAndProjectId(id, projectId).orElseThrow(() -> new OutlineVersionNotFoundException(id));
    }
    private OutlineResponse response(OutlineVersion value) {
        OutlineContent content = characterNames.render(value.getProjectId(), value.getContent(), OutlineContent.class);
        return OutlineResponse.from(value, content);
    }
    private NovelProject requireOwnedProject(UUID id) {
        return projects.findById(id).filter(value -> value.getOwnerId().equals(actorProvider.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(id));
    }
    private static void checkVersion(OutlineVersion value, long expected) {
        if (value.getRowVersion() != expected) throw new ResourceVersionConflictException(expected, value.getRowVersion());
    }
    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
