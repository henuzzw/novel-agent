package com.novelagent.planning.application;

import com.novelagent.planning.api.GenerateStoryDirectionsRequest;
import com.novelagent.planning.api.StoryDirectionSetResponse;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.OutlineWordBudgetPolicy;
import com.novelagent.planning.domain.StoryDirectionSet;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import com.novelagent.planning.infrastructure.StoryDirectionSetRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoryDirectionService {

    private final NovelProjectRepository projectRepository;
    private final CreativeIntentRepository creativeIntentRepository;
    private final StoryDirectionSetRepository directionSetRepository;
    private final StoryDirectionGenerationWorkflow generationWorkflow;
    private final OutlineWordBudgetPolicy wordBudgetPolicy;
    private final CurrentActorProvider actorProvider;

    public StoryDirectionService(
            NovelProjectRepository projectRepository,
            CreativeIntentRepository creativeIntentRepository,
            StoryDirectionSetRepository directionSetRepository,
            StoryDirectionGenerationWorkflow generationWorkflow,
            OutlineWordBudgetPolicy wordBudgetPolicy,
            CurrentActorProvider actorProvider) {
        this.projectRepository = projectRepository;
        this.creativeIntentRepository = creativeIntentRepository;
        this.directionSetRepository = directionSetRepository;
        this.generationWorkflow = generationWorkflow;
        this.wordBudgetPolicy = wordBudgetPolicy;
        this.actorProvider = actorProvider;
    }

    public StoryDirectionSetResponse generate(UUID projectId, GenerateStoryDirectionsRequest request) {
        requireOwnedProject(projectId);
        CreativeIntent intent = creativeIntentRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("请先完善创作意图，再生成故事方向"));
        CreativeIntentSnapshot snapshot = CreativeIntentSnapshot.from(intent);
        wordBudgetPolicy.plan(snapshot.targetWords());
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        Optional<StoryDirectionSet> previous = directionSetRepository
                .findFirstByProjectIdOrderByGenerationNumberDesc(projectId);
        List<StoryDirectionCandidate> previousDirections =
                request.mode() == GenerationMode.REGENERATE
                        ? List.of()
                        : previous.map(StoryDirectionSet::getDirections).orElseGet(List::of);
        GeneratedStoryDirections generated = generationWorkflow
                .generate(projectId, snapshot, provider, previousDirections, normalize(request.instruction()));
        int generationNumber = previous
                .map(existing -> existing.getGenerationNumber() + 1)
                .orElse(1);

        StoryDirectionSet set = StoryDirectionSet.create(
                UUID.randomUUID(),
                projectId,
                generationNumber,
                generated.generatorType(),
                normalize(request.instruction()),
                snapshot,
                generated.directions(),
                generated.questionsForAuthor(),
                generated.changeSummary());
        StoryDirectionSet saved = directionSetRepository.saveAndFlush(set);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Optional<StoryDirectionSetResponse> latest(UUID projectId) {
        requireOwnedProject(projectId);
        return directionSetRepository.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)
                .map(this::toResponse);
    }

    @Transactional
    public StoryDirectionSetResponse select(UUID projectId, UUID setId, long expectedVersion, UUID candidateId) {
        requireOwnedProject(projectId);
        StoryDirectionSet set = directionSetRepository.findByIdAndProjectId(setId, projectId)
                .orElseThrow(() -> new StoryDirectionSetNotFoundException(setId));
        if (set.getRowVersion() != expectedVersion) {
            throw new ResourceVersionConflictException(expectedVersion, set.getRowVersion());
        }
        set.select(candidateId);
        return toResponse(directionSetRepository.saveAndFlush(set));
    }

    private void requireOwnedProject(UUID projectId) {
        boolean owned = projectRepository.findById(projectId)
                .filter(project -> project.getOwnerId().equals(actorProvider.currentUserId()))
                .isPresent();
        if (!owned) {
            throw new ProjectNotFoundException(projectId);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private StoryDirectionSetResponse toResponse(StoryDirectionSet set) {
        return StoryDirectionSetResponse.from(
                set,
                wordBudgetPolicy.plan(set.getInputSnapshot().targetWords()));
    }
}
