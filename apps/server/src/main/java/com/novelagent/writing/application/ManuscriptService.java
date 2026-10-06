package com.novelagent.writing.application;

import static com.novelagent.writing.application.WritingChecks.check;
import static com.novelagent.writing.application.WritingChecks.normalize;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.memory.application.ContextBudgetPlanner;
import com.novelagent.memory.application.MemoryBudgetPlan;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.planning.application.GenerationMode;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.ManuscriptVersionSummaryResponse;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 章节正文。
 *
 * <p>依据已确认合同生成单份正文草稿，并维护编辑、手动修订、作者确认和导出。生成复核上游依据、保存来源合同及基准稿关联；作者确认不等于提交正史。</p>
 */
@Service
public class ManuscriptService {
    private final WritingContextService contexts;
    private final ChapterContractVersionRepository contracts;
    private final ManuscriptVersionRepository manuscripts;
    private final WritingGenerationWorkflow workflow;
    private final ContextBudgetPlanner budgetPlanner;
    private final CharacterNameService characterNames;
    private final WritingStyleService styles;

    public ManuscriptService(WritingContextService contexts, ChapterContractVersionRepository contracts,
            ManuscriptVersionRepository manuscripts, WritingGenerationWorkflow workflow,
            ContextBudgetPlanner budgetPlanner, CharacterNameService characterNames, WritingStyleService styles) {
        this.contexts = contexts;
        this.contracts = contracts;
        this.manuscripts = manuscripts;
        this.workflow = workflow;
        this.budgetPlanner = budgetPlanner;
        this.characterNames = characterNames;
        this.styles = styles;
    }

    /**
     * 读取本章最新保存正文并按当前人物名称渲染，最新草稿不等于作者接受或有效正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<ManuscriptResponse> latestManuscript(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(this::manuscriptResponse);
    }

    /**
     * 列出本章正文版本摘要，供作者选择查看或修订基准，不切换有效正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<ManuscriptVersionSummaryResponse> manuscriptVersions(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .stream().map(value -> ManuscriptVersionSummaryResponse.from(value,
                        characterNames.render(projectId, value.getContent().title()))).toList();
    }

    /**
     * 按项目、章号及版本 ID 读取指定正文，保留其合同来源、基准稿与状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true)
    public ManuscriptResponse manuscriptVersion(UUID projectId, int chapterNumber, UUID id) {
        contexts.requireOwnedProject(projectId);
        return manuscriptResponse(manuscripts.findByIdAndProjectIdAndChapterNumber(id, projectId, chapterNumber)
                .orElseThrow(() -> new WritingResourceNotFoundException("正文版本", id)));
    }

    /**
     * 依据本章已确认且属于当前大纲的合同生成一份新正文草稿，复核上游来源后保存，不直接确认或提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public ManuscriptResponse generateManuscript(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        return manuscriptResponse(manuscripts.saveAndFlush(prepareManuscript(projectId, chapterNumber, request)));
    }

    /**
     * 构建未持久化的新正文版本：读取确认合同、分配记忆预算、调用模型并复核合同、基准稿与风格。调用方负责随后保存。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    ManuscriptVersion prepareManuscript(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        WritingContextService.Context context = contexts.context(projectId, chapterNumber);
        WritingBasisSnapshot basis = WritingBasisSnapshot.capture(context);
        ChapterContractVersion contract = contracts
                .findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                        projectId, chapterNumber, ChapterContractStatus.APPROVED)
                .orElseThrow(() -> new IllegalArgumentException("请先生成并确认本章的章节合同"));
        if (!contract.getSourceOutlineVersionId().equals(context.outline().getId())) {
            throw new IllegalArgumentException("章节合同来自旧大纲，请按当前已发布大纲重新生成并确认");
        }
        long contractRowVersion = contract.getRowVersion();
        ChapterContractContentSnapshot contractSnapshot = new ChapterContractContentSnapshot(contract.getId(),
                contractRowVersion, contract.getContent());
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        Optional<ManuscriptVersion> latest = manuscripts
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber);
        if (request.mode() == GenerationMode.REGENERATE && request.baseManuscriptVersionId() != null) {
            throw new IllegalArgumentException("重新创作时不能指定基准正文版本");
        }
        ManuscriptVersion base = request.mode() == GenerationMode.REGENERATE ? null
                : request.baseManuscriptVersionId() == null ? latest.orElse(null)
                : manuscripts.findByIdAndProjectIdAndChapterNumber(
                        request.baseManuscriptVersionId(), projectId, chapterNumber)
                        .orElseThrow(() -> new WritingResourceNotFoundException(
                                "正文版本", request.baseManuscriptVersionId()));
        ManuscriptContent previousContent = base == null ? null : base.getContent();
        String styleContext = styles.promptContext(projectId);
        MemoryBudgetPlan budget = budgetPlanner.plan(AgentStage.MANUSCRIPT, provider,
                context.budgetInputs(context.bible().getContent(), context.arc(), context.chapter(), contract.getContent(),
                        previousContent, request.instruction(), styleContext));
        NovelMemoryContext recalled = contexts.recall(AgentStage.MANUSCRIPT, context, request.instruction(), budget);
        GeneratedManuscript generated = workflow.generateManuscript(projectId, context.bible().getContent(), context.arc(),
                context.chapter(), contract.getContent(), recalled, previousContent, provider,
                context.instructionWithPreparation(normalize(request.instruction())));
        basis.requireUnchanged(contexts.context(projectId, chapterNumber));
        ChapterContractVersion currentContract = contracts
                .findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                        projectId, chapterNumber, ChapterContractStatus.APPROVED).orElseThrow();
        if (!contractSnapshot.matches(currentContract)
                || !java.util.Objects.equals(styleContext, styles.promptContext(projectId))) {
            throw new IllegalStateException("生成期间章节合同或写作风格已变化，请刷新后重试");
        }
        if (base != null) {
            ManuscriptVersion currentBase = manuscripts.findByIdAndProjectIdAndChapterNumber(
                    base.getId(), projectId, chapterNumber).orElseThrow();
            if (!java.util.Objects.equals(previousContent, currentBase.getContent())) {
                throw new IllegalStateException("生成期间基准正文已变化，请刷新后重试");
            }
        }
        ManuscriptContent generatedContent = characterNames.tokenize(projectId, generated.content());
        int version = latest.map(value -> value.getVersionNumber() + 1).orElse(1);
        return ManuscriptVersion.create(UUID.randomUUID(), projectId, contract.getId(),
                chapterNumber, version, provider.name(), normalize(request.instruction()),
                base == null ? null : base.getId(), generatedContent,
                generated.changeSummary());
    }

    /**
     * 限定当前正文及源行版本构建质量修订候选；本地模板仅演示版本流程，不声称完成语义润色。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param sourceId 本次处理的基准记录 ID，不隐式改用最新版本。
     * @param sourceVersion 基准来源的预期编辑行版本。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     * @param feedback 来自作者选中问题的修订要求，不能扩大事实或授权范围。
     */
    ManuscriptVersion prepareQualityRevision(UUID projectId, int chapterNumber, UUID sourceId,
            long sourceVersion, ModelProvider provider, String feedback) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion source = requireManuscript(projectId, sourceId);
        check(source.getRowVersion(), sourceVersion);
        if (source.getChapterNumber() != chapterNumber || !manuscripts
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber).orElseThrow()
                .getId().equals(sourceId)) throw new IllegalStateException("请从当前正文进行润色");
        if (provider == null || provider == ModelProvider.LOCAL_TEMPLATE) {
            return ManuscriptVersion.create(UUID.randomUUID(), projectId, source.getSourceContractVersionId(),
                    chapterNumber, source.getVersionNumber() + 1, ModelProvider.LOCAL_TEMPLATE.name(), feedback,
                    sourceId, source.getContent(), List.of("本地模板仅创建版本流程候选，保留原稿，未执行语义润色。"));
        }
        return prepareManuscript(projectId, chapterNumber, new GenerateWritingRequest(provider, feedback,
                GenerationMode.REVISE, sourceId, null));
    }

    /**
     * 按行版本保存作者对可编辑正文的修改，保持该版本的业务状态约束。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional
    public ManuscriptResponse updateManuscript(UUID projectId, UUID id, long expected, ManuscriptContent content) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion value = requireManuscript(projectId, id);
        check(value.getRowVersion(), expected);
        value.revise(characterNames.tokenize(projectId, content));
        return manuscriptResponse(manuscripts.saveAndFlush(value));
    }

    /**
     * 将当前作者已确认正文复制为人工修订草稿，原稿保留且新稿记录基准来源；不调用模型。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param sourceId 本次处理的基准记录 ID，不隐式改用最新版本。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     */
    @Transactional
    public ManuscriptResponse createManuscriptRevision(UUID projectId, int chapterNumber, UUID sourceId, long expected) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion source = manuscripts.findByIdAndProjectIdAndChapterNumber(sourceId, projectId, chapterNumber)
                .orElseThrow(() -> new WritingResourceNotFoundException("正文版本", sourceId));
        check(source.getRowVersion(), expected);
        if (source.getStatus() != ManuscriptStatus.AUTHOR_ACCEPTED) {
            throw new IllegalArgumentException("只有作者已确认的正文才能创建人工修订草稿");
        }
        ManuscriptVersion latest = manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(
                projectId, chapterNumber).orElseThrow();
        if (!latest.getId().equals(sourceId)) {
            throw new IllegalStateException("当前正文已有更新，请刷新后再创建修订草稿");
        }
        ManuscriptVersion revision = ManuscriptVersion.create(UUID.randomUUID(), projectId,
                source.getSourceContractVersionId(), chapterNumber, latest.getVersionNumber() + 1,
                "AUTHOR_EDIT", null, sourceId, source.getContent(), List.of());
        return manuscriptResponse(manuscripts.saveAndFlush(revision));
    }

    /**
     * 由作者显式确认正文版本；这里只改变正文接受状态，不抽取事实或提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     */
    @Transactional
    public ManuscriptResponse acceptManuscript(UUID projectId, UUID id, long expected) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion value = requireManuscript(projectId, id);
        check(value.getRowVersion(), expected);
        value.accept();
        return manuscriptResponse(manuscripts.saveAndFlush(value));
    }

    private ManuscriptVersion requireManuscript(UUID projectId, UUID id) {
        return manuscripts.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new WritingResourceNotFoundException("正文版本", id));
    }
    /**
     * 以当前人物显示名称导出选定正文的 Markdown 内容，不修改正文或业务状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true)
    public String exportManuscript(UUID projectId, UUID id) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion value = requireManuscript(projectId, id);
        ManuscriptContent rendered = characterNames.render(projectId, value.getContent());
        return "# " + rendered.title() + "\n\n" + rendered.body();
    }

    private ManuscriptResponse manuscriptResponse(ManuscriptVersion value) {
        return ManuscriptResponse.from(value, renderContent(value));
    }
    /**
     * 将存储正文的稳定实体引用渲染为当前姓名，只影响返回内容，不重写数据库版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     */
    ManuscriptContent renderContent(ManuscriptVersion value) {
        return characterNames.render(value.getProjectId(), value.getContent());
    }

    private record ChapterContractContentSnapshot(UUID id, long rowVersion,
            com.novelagent.writing.domain.ChapterContractContent content) {
        boolean matches(ChapterContractVersion value) {
            return id.equals(value.getId()) && rowVersion == value.getRowVersion()
                    && java.util.Objects.equals(content, value.getContent());
        }
    }
}
