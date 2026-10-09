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
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分层大纲。
 *
 * <p>依据发布圣经和项目策略生成或有限调整分层大纲。生成前后核对依据，作者显式发布时更新当前指针并同步明确台账；草稿不是正史。</p>
 */
@Service
public class OutlineService {
    private final NovelProjectRepository projects;
    private final CreativeIntentRepository intents;
    private final StoryBibleVersionRepository bibles;
    private final OutlineVersionRepository outlines;
    private final OutlineWordBudgetPolicy budgetPolicy;
    private final OutlineGenerationWorkflow workflow;
    private final ProjectAccessService access;
    private final CharacterNameService characterNames;
    private final PlanningMaterialSyncService materials;
    private com.novelagent.ingest.application.ImportedOutlineGenerator importedGenerator;

    @org.springframework.beans.factory.annotation.Autowired
    public void setImportedGenerator(com.novelagent.ingest.application.ImportedOutlineGenerator generator) {
        this.importedGenerator = generator;
    }

    public OutlineService(
            NovelProjectRepository projects,
            CreativeIntentRepository intents,
            StoryBibleVersionRepository bibles,
            OutlineVersionRepository outlines,
            OutlineWordBudgetPolicy budgetPolicy,
            OutlineGenerationWorkflow workflow,
            ProjectAccessService access,
            CharacterNameService characterNames,
            PlanningMaterialSyncService materials) {
        this.projects = projects;
        this.intents = intents;
        this.bibles = bibles;
        this.outlines = outlines;
        this.budgetPolicy = budgetPolicy;
        this.workflow = workflow;
        this.access = access;
        this.characterNames = characterNames;
        this.materials = materials;
    }

    /**
     * 读取项目当前发布圣经及字数预算、策略，按重新规划或指定基准模式调用模型；保存完整新大纲草稿，不自动替换发布指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public OutlineResponse generate(UUID projectId, GenerateOutlineRequest request) {
        NovelProject project = access.requireOwnedProject(projectId);
        UUID bibleId = project.getCurrentBibleVersionId();
        if (bibleId == null) throw new IllegalArgumentException("请先发布故事圣经，再生成分层大纲");
        StoryBibleVersion bible = bibles.findByIdAndProjectId(bibleId, projectId)
                .filter(value -> value.getStatus() == StoryBibleStatus.PUBLISHED)
                .orElseThrow(() -> new IllegalArgumentException("项目当前故事圣经不可用，请重新发布"));
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        Optional<OutlineVersion> latest = outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId);
        if (request.mode() == GenerationMode.REGENERATE && request.baseOutlineVersionId() != null) {
            throw new IllegalArgumentException("重新生成时不能指定基准大纲版本");
        }
        OutlineVersion base = request.mode() == GenerationMode.REGENERATE ? null
                : request.baseOutlineVersionId() == null ? latest.orElse(null)
                : requireVersion(projectId, request.baseOutlineVersionId());
        OutlineWordBudget budget = resolveBudget(projectId, base, latest);
        StoryBibleContent promptBible = characterNames.render(projectId, bible.getContent(), StoryBibleContent.class);
        OutlineContent previousContent = base == null ? null
                : characterNames.render(projectId, base.getContent(), OutlineContent.class);
        GeneratedOutline generated = importedGenerator != null && bible.getSourceImportId() != null
                && bible.getSourceDirectionSetId() != null
                ? importedGenerator.generate(projectId, bible, budget, provider, previousContent,
                        normalize(request.instruction()), CreativeStrategyPolicy.from(project))
                : workflow.generate(projectId, promptBible, budget, provider,
                        previousContent, normalize(request.instruction()), CreativeStrategyPolicy.from(project));
        int generation = latest
                .map(value -> value.getGenerationNumber() + 1).orElse(1);
        OutlineVersion version = OutlineVersion.create(UUID.randomUUID(), projectId, generation,
                generated.generatorType(), normalize(request.instruction()), bibleId,
                base == null ? null : base.getId(), budget,
                generated.content().reviewScenesAgainst(previousContent, base != null && !bibleId.equals(base.getSourceBibleVersionId())),
                generated.changeSummary());
        return response(outlines.saveAndFlush(version));
    }

    /** Imported planning can exist without a separate intent; reuse its saved capacity, not invented author preferences. */
    private OutlineWordBudget resolveBudget(UUID projectId, OutlineVersion base, Optional<OutlineVersion> latest) {
        Optional<CreativeIntent> intent = intents.findById(projectId);
        if (intent.isPresent()) return budgetPolicy.plan(intent.get().getTargetWords());
        OutlineVersion source = base != null ? base : latest.orElse(null);
        OutlineWordBudget saved = source == null ? null : source.getWordBudget();
        if (saved == null || saved.targetWords() < 1000) {
            throw new IllegalArgumentException("项目尚未设置目标字数，也没有可沿用的大纲篇幅；请在故事方向中保存创作要求后再生成大纲");
        }
        budgetPolicy.validate(saved);
        return saved;
    }

    /**
     * 按生成顺序返回历史版本摘要，供作者显式选择基准，不修改当前发布指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<OutlineVersionSummaryResponse> versions(UUID projectId) {
        access.requireOwnedProject(projectId);
        return outlines.findAllByProjectIdOrderByGenerationNumberDesc(projectId).stream()
                .map(value -> OutlineVersionSummaryResponse.from(value,
                        characterNames.render(projectId, value.getContent().title())))
                .toList();
    }

    /**
     * 读取指定版本并限定所属项目；版本 ID 与用于并发编辑的行版本是不同概念。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param outlineId 大纲版本 ID，与生成序号和行版本不同。
     */
    @Transactional(readOnly = true)
    public OutlineResponse version(UUID projectId, UUID outlineId) {
        access.requireOwnedProject(projectId);
        return response(requireVersion(projectId, outlineId));
    }

    /**
     * 读取最新保存结果；“最新”不自动表示已发布、已确认或已进入正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<OutlineResponse> latest(UUID projectId) {
        access.requireOwnedProject(projectId);
        return outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId).map(this::response);
    }

    /**
     * 读取项目当前已发布规划指针对应的版本，不能用最新草稿替代正式创作依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<OutlineResponse> current(UUID projectId) {
        NovelProject project = access.requireOwnedProject(projectId);
        UUID currentId = project.getCurrentOutlineVersionId();
        if (currentId == null) return Optional.empty();
        return Optional.of(response(requireVersion(projectId, currentId)));
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param outlineId 大纲版本 ID，与生成序号和行版本不同。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional
    public OutlineResponse update(UUID projectId, UUID outlineId, long expectedVersion, OutlineContent content) {
        access.requireOwnedProject(projectId);
        OutlineVersion version = requireVersion(projectId, outlineId);
        checkVersion(version, expectedVersion);
        if (version.getStatus() == OutlineStatus.PUBLISHED)
            throw new IllegalArgumentException("已发布的大纲不能直接修改，请生成新版本");
        version.revise(content.reviewScenesAgainst(version.getContent(), false));
        return response(outlines.saveAndFlush(version));
    }

    /**
     * 由作者显式发布指定规划版本，更新项目当前依据并执行该规划对应的资料同步；不提交正文正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param outlineId 大纲版本 ID，与生成序号和行版本不同。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     */
    @Transactional
    public OutlineResponse publish(UUID projectId, UUID outlineId, long expectedVersion) {
        NovelProject project = access.requireOwnedProject(projectId);
        OutlineVersion version = requireVersion(projectId, outlineId);
        checkVersion(version, expectedVersion);
        version.publish();
        project.publishOutline(version.getId());
        projects.save(project);
        materials.syncOutline(version);
        return response(outlines.saveAndFlush(version));
    }

    private OutlineVersion requireVersion(UUID projectId, UUID id) {
        return outlines.findByIdAndProjectId(id, projectId).orElseThrow(() -> new OutlineVersionNotFoundException(id));
    }
    private OutlineResponse response(OutlineVersion value) {
        OutlineContent content = characterNames.render(value.getProjectId(), value.getContent(), OutlineContent.class);
        return OutlineResponse.from(value, content);
    }
    private static void checkVersion(OutlineVersion value, long expected) {
        if (value.getRowVersion() != expected) throw new ResourceVersionConflictException(expected, value.getRowVersion());
    }
    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
