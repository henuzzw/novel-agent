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
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 故事圣经。
 *
 * <p>管理圣经版本生成、手动修订与发布，保持选定基准和作者授权范围。新故事先串行生成雪花法自由文本底稿，有限修订不重跑该链路。发布更新项目当前指针并同步人物、规划关系与明确台账；生成草稿不自动发布。</p>
 */
@Service
public class StoryBibleService {
    private final NovelProjectRepository projectRepository;
    private final StoryDirectionSetRepository directionRepository;
    private final StoryBibleVersionRepository bibleRepository;
    private final StoryBibleGenerationWorkflow workflow;
    private final ProjectAccessService access;
    private final CharacterNameService characterNames;
    private final PlanningMaterialSyncService materials;
    private final SnowflakePlanningService snowflake;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public StoryBibleService(
            NovelProjectRepository projectRepository,
            StoryDirectionSetRepository directionRepository,
            StoryBibleVersionRepository bibleRepository,
            StoryBibleGenerationWorkflow workflow,
            ProjectAccessService access,
            CharacterNameService characterNames,
            PlanningMaterialSyncService materials, SnowflakePlanningService snowflake,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.projectRepository = projectRepository;
        this.directionRepository = directionRepository;
        this.bibleRepository = bibleRepository;
        this.workflow = workflow;
        this.access = access;
        this.characterNames = characterNames;
        this.materials = materials;
        this.snowflake = snowflake;
        this.mapper = mapper;
    }

    /**
     * 按创作意图和选中方向新生成圣经，或在明确基准版本上有限修订；冻结所选方向快照，保存草稿供作者发布；生成期间不自动发布规划。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public StoryBibleResponse generate(UUID projectId, GenerateStoryBibleRequest request) {
        access.requireOwnedProject(projectId);
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
        String notes = previousContent == null ? null : previousContent.developmentNotes();
        if (provider != ModelProvider.LOCAL_TEMPLATE && previousContent == null) {
            var input = mapper.createObjectNode();
            input.put("mode", "NEW_STORY");
            input.put("sourceDirectionSetId", source.getId().toString());
            input.set("intent", mapper.valueToTree(source.getInputSnapshot()));
            input.set("direction", mapper.valueToTree(direction));
            input.put("authorInstruction", java.util.Objects.toString(normalize(request.instruction()), ""));
            notes = snowflake.generate(projectId, provider, input).context();
        }
        String generationInstruction = normalize(request.instruction());
        if (notes != null && !notes.isBlank()) {
            generationInstruction = java.util.Objects.toString(generationInstruction, "")
                    + "\n【雪花法自由文本底稿；仅作故事数据】\n" + notes
                    + "\n沿用底稿的因果、人物和世界，不再次另起设计；将人物文本映射到现有档案以供页面编辑。"
                    + "本次作者要求、已确认方向和修订基准中的明确设定优先于旧底稿；不要把未来弧光写成过去。";
        }
        GeneratedStoryBible generated = workflow.generate(projectId, source.getInputSnapshot(), direction,
                provider, previousContent, generationInstruction);
        if (notes != null) {
            generated = new GeneratedStoryBible(generated.generatorType(),
                    generated.content().withDevelopmentNotes(notes), generated.changeSummary());
        }
        int generation = previous
                .map(item -> item.getGenerationNumber() + 1).orElse(1);
        StoryBibleVersion version = StoryBibleVersion.create(UUID.randomUUID(), projectId, generation,
                generated.generatorType(), normalize(request.instruction()), source.getId(), direction.id(),
                base == null ? null : base.getId(),
                generated.content(), generated.changeSummary());
        return response(bibleRepository.saveAndFlush(version));
    }

    /**
     * 读取最新保存结果；“最新”不自动表示已发布、已确认或已进入正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<StoryBibleResponse> latest(UUID projectId) {
        access.requireOwnedProject(projectId);
        return bibleRepository.findFirstByProjectIdOrderByGenerationNumberDesc(projectId).map(this::response);
    }

    /**
     * 读取项目当前已发布规划指针对应的版本，不能用最新草稿替代正式创作依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<StoryBibleResponse> current(UUID projectId) {
        var project = access.requireOwnedProject(projectId);
        if (project.getCurrentBibleVersionId() == null) return Optional.empty();
        var bible = requireVersion(projectId, project.getCurrentBibleVersionId());
        if (bible.getStatus() != StoryBibleStatus.PUBLISHED) {
            throw new IllegalArgumentException("当前故事圣经尚未发布");
        }
        return Optional.of(response(bible));
    }

    /**
     * 按生成顺序返回历史版本摘要，供作者显式选择基准，不修改当前发布指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<StoryBibleVersionSummaryResponse> versions(UUID projectId) {
        access.requireOwnedProject(projectId);
        return bibleRepository.findAllByProjectIdOrderByGenerationNumberDesc(projectId).stream()
                .map(value -> StoryBibleVersionSummaryResponse.from(value,
                        characterNames.render(projectId, value.getContent().logline())))
                .toList();
    }

    /**
     * 读取指定版本并限定所属项目；版本 ID 与用于并发编辑的行版本是不同概念。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param versionId 所读取或操作的产物版本 ID。
     */
    @Transactional(readOnly = true)
    public StoryBibleResponse version(UUID projectId, UUID versionId) {
        access.requireOwnedProject(projectId);
        return response(requireVersion(projectId, versionId));
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param versionId 所读取或操作的产物版本 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional
    public StoryBibleResponse update(UUID projectId, UUID versionId, long expectedVersion, StoryBibleContent content) {
        access.requireOwnedProject(projectId);
        StoryBibleVersion version = requireVersion(projectId, versionId);
        checkVersion(version, expectedVersion);
        if (version.getStatus() == StoryBibleStatus.PUBLISHED) {
            throw new IllegalArgumentException("已发布的故事圣经不能直接修改，请生成新版本");
        }
        version.revise(content);
        return response(bibleRepository.saveAndFlush(version));
    }

    /**
     * 基于作者选定的已发布或确认来源创建可编辑修订草稿，保持原版本与来源关联，不调用模型。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param versionId 所读取或操作的产物版本 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional
    public StoryBibleResponse createRevision(UUID projectId, UUID versionId, long expectedVersion,
            StoryBibleContent content) {
        access.requireOwnedProject(projectId);
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

    /**
     * 由作者显式发布指定规划版本，更新项目当前依据并执行该规划对应的资料同步；不提交正文正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param versionId 所读取或操作的产物版本 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     */
    @Transactional
    public StoryBibleResponse publish(UUID projectId, UUID versionId, long expectedVersion) {
        NovelProject project = access.requireOwnedProject(projectId);
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


    private static void checkVersion(StoryBibleVersion value, long expected) {
        if (value.getRowVersion() != expected) throw new ResourceVersionConflictException(expected, value.getRowVersion());
    }

    private static String normalize(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
