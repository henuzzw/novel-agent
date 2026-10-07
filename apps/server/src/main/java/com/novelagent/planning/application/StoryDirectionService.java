package com.novelagent.planning.application;

import com.novelagent.planning.api.GenerateStoryDirectionsRequest;
import com.novelagent.planning.api.StoryDirectionSetResponse;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.OutlineWordBudgetPolicy;
import com.novelagent.planning.domain.StoryDirectionSet;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import com.novelagent.planning.infrastructure.StoryDirectionSetRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 故事方向。
 *
 * <p>按创作意图快照生成候选方向，并校验集合行版本后保存作者选择。模型调用与集合保存分开，不在此生成圣经，也不改写创作意图。</p>
 */
@Service
public class StoryDirectionService {

    private final CreativeIntentRepository creativeIntentRepository;
    private final StoryDirectionSetRepository directionSetRepository;
    private final StoryDirectionGenerationWorkflow generationWorkflow;
    private final OutlineWordBudgetPolicy wordBudgetPolicy;
    private final ProjectAccessService access;

    public StoryDirectionService(
            CreativeIntentRepository creativeIntentRepository,
            StoryDirectionSetRepository directionSetRepository,
            StoryDirectionGenerationWorkflow generationWorkflow,
            OutlineWordBudgetPolicy wordBudgetPolicy,
            ProjectAccessService access) {
        this.creativeIntentRepository = creativeIntentRepository;
        this.directionSetRepository = directionSetRepository;
        this.generationWorkflow = generationWorkflow;
        this.wordBudgetPolicy = wordBudgetPolicy;
        this.access = access;
    }

    /**
     * 读取创作意图快照与字数预算，在事务外请求候选方向并保存新集合；旧候选是否入模服从生成模式，不自动选择或生成圣经。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public StoryDirectionSetResponse generate(UUID projectId, GenerateStoryDirectionsRequest request) {
        access.requireOwnedProject(projectId);
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

    /**
     * 读取最新保存结果；“最新”不自动表示已发布、已确认或已进入正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<StoryDirectionSetResponse> latest(UUID projectId) {
        access.requireOwnedProject(projectId);
        return directionSetRepository.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)
                .map(this::toResponse);
    }

    /**
     * 限定方向集合及预期行版本，保存作者选中的候选 ID；不更新创作意图，也不隐式调用圣经生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param setId 故事方向候选集合 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param candidateId 作者选中的集合内候选方向 ID。
     */
    @Transactional
    public StoryDirectionSetResponse select(UUID projectId, UUID setId, long expectedVersion, UUID candidateId) {
        access.requireOwnedProject(projectId);
        StoryDirectionSet set = directionSetRepository.findByIdAndProjectId(setId, projectId)
                .orElseThrow(() -> new StoryDirectionSetNotFoundException(setId));
        if (set.getRowVersion() != expectedVersion) {
            throw new ResourceVersionConflictException(expectedVersion, set.getRowVersion());
        }
        set.select(candidateId);
        return toResponse(directionSetRepository.saveAndFlush(set));
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
