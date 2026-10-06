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
import com.novelagent.writing.api.ChapterContractResponse;
import com.novelagent.writing.api.ChapterContractReviewResponse;
import com.novelagent.writing.api.ChapterContractVersionSummaryResponse;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractReviewContent;
import com.novelagent.writing.domain.ChapterContractReviewVersion;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ReviewStatus;
import com.novelagent.writing.infrastructure.ChapterContractReviewVersionRepository;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 章节合同。
 *
 * <p>生成和编辑可执行节拍，独立审阅后才能由作者确认合同。模型等待不包在长事务中，完成后核对主要来源；审阅必须对应当前合同及行版本，不能用旧审阅批准新合同。</p>
 */
@Service
public class ChapterContractService {
    private final WritingContextService contexts;
    private final ChapterContractVersionRepository contracts;
    private final ChapterContractReviewVersionRepository contractReviews;
    private final WritingGenerationWorkflow workflow;
    private final ContextBudgetPlanner budgetPlanner;
    private final CharacterNameService characterNames;

    public ChapterContractService(WritingContextService contexts, ChapterContractVersionRepository contracts,
            ChapterContractReviewVersionRepository contractReviews, WritingGenerationWorkflow workflow,
            ContextBudgetPlanner budgetPlanner, CharacterNameService characterNames) {
        this.contexts = contexts;
        this.contracts = contracts;
        this.contractReviews = contractReviews;
        this.workflow = workflow;
        this.budgetPlanner = budgetPlanner;
        this.characterNames = characterNames;
    }

    /**
     * 读取本章最新保存合同，可能仍是草稿；不代表已通过审阅或作者确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<ChapterContractResponse> latestContract(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(ChapterContractResponse::from);
    }

    /**
     * 列出本章历史合同版本摘要，供作者显式选择生成基准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<ChapterContractVersionSummaryResponse> contractVersions(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return contracts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .stream().map(value -> ChapterContractVersionSummaryResponse.from(value,
                        characterNames.render(projectId, value.getContent().chapterTitle()))).toList();
    }

    /**
     * 按项目、章号和版本 ID 读取指定合同，避免把其他章节版本用作当前依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true)
    public ChapterContractResponse contractVersion(UUID projectId, int chapterNumber, UUID id) {
        contexts.requireOwnedProject(projectId);
        return ChapterContractResponse.from(contracts.findByIdAndProjectIdAndChapterNumber(id, projectId, chapterNumber)
                .orElseThrow(() -> new WritingResourceNotFoundException("章节合同版本", id)));
    }

    /**
     * 根据当前发布规划及记忆生成或有限调整合同草稿，模型返回后核对主要来源；不自动审阅或确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public ChapterContractResponse generateContract(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        WritingContextService.Context context = contexts.context(projectId, chapterNumber);
        WritingBasisSnapshot basis = WritingBasisSnapshot.capture(context);
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        GenerationMode mode = request.mode() == null ? GenerationMode.REGENERATE : request.mode();
        Optional<ChapterContractVersion> latest = contracts
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber);
        if (mode == GenerationMode.REGENERATE && request.baseContractVersionId() != null) {
            throw new IllegalArgumentException("重新生成时不能指定基准章节合同版本");
        }
        ChapterContractVersion base = mode == GenerationMode.REGENERATE ? null
                : request.baseContractVersionId() == null ? latest.orElse(null)
                : contracts.findByIdAndProjectIdAndChapterNumber(request.baseContractVersionId(), projectId, chapterNumber)
                        .orElseThrow(() -> new WritingResourceNotFoundException(
                                "章节合同版本", request.baseContractVersionId()));
        ChapterContractContent previousContent = base == null ? null : base.getContent();
        MemoryBudgetPlan budget = budgetPlanner.plan(AgentStage.CHAPTER_CONTRACT, provider,
                context.budgetInputs(context.bible().getContent(), context.arc(), context.chapter(), previousContent, request.instruction()));
        NovelMemoryContext recalled = contexts.recall(AgentStage.CHAPTER_CONTRACT, context, request.instruction(), budget);
        ChapterContractContent generated = workflow.generateContract(projectId, context.bible().getContent(), context.arc(),
                context.chapter(), recalled, previousContent, provider, context.instructionWithPreparation(normalize(request.instruction())));
        basis.requireUnchanged(contexts.context(projectId, chapterNumber));
        int version = latest.map(value -> value.getVersionNumber() + 1).orElse(1);
        ChapterContractVersion result = ChapterContractVersion.create(UUID.randomUUID(), projectId,
                context.outline().getId(), chapterNumber, version, provider.name(), normalize(request.instruction()),
                base == null ? null : base.getId(), generated);
        return ChapterContractResponse.from(contracts.saveAndFlush(result));
    }

    /**
     * 读取本章最新合同审阅报告；是否可用于批准合同还需核对来源版本和状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<ChapterContractReviewResponse> latestContractReview(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return contractReviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(ChapterContractReviewResponse::from);
    }

    /**
     * 独立审阅当前大纲下的合同草稿，保存与合同 ID 及行版本绑定的报告，不直接批准合同。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    public ChapterContractReviewResponse generateContractReview(UUID projectId, int chapterNumber,
            GenerateWritingRequest request) {
        WritingContextService.Context context = contexts.context(projectId, chapterNumber);
        WritingBasisSnapshot basis = WritingBasisSnapshot.capture(context);
        long contractRowVersion;
        ChapterContractVersion contract = contracts
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("请先生成并保存本章合同"));
        if (!contract.getSourceOutlineVersionId().equals(context.outline().getId())) {
            throw new IllegalArgumentException("章节合同来自旧大纲，请按当前已发布大纲重新生成");
        }
        if (contract.getStatus() != ChapterContractStatus.DRAFT) {
            throw new IllegalArgumentException("已确认合同无需重新审阅，请先生成新合同草稿");
        }
        contractRowVersion = contract.getRowVersion();
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        MemoryBudgetPlan budget = budgetPlanner.plan(AgentStage.CHAPTER_CONTRACT, provider,
                context.budgetInputs(context.bible().getContent(), context.arc(), context.chapter(), contract.getContent(), request.instruction()));
        NovelMemoryContext recalled = contexts.recall(AgentStage.CHAPTER_CONTRACT, context, request.instruction(), budget);
        ChapterContractReviewContent generated = workflow.generateContractReview(projectId,
                context.bible().getContent(), context.arc(), context.chapter(), contract.getContent(), recalled,
                provider, context.instructionWithPreparation(normalize(request.instruction())));
        basis.requireUnchanged(contexts.context(projectId, chapterNumber));
        ChapterContractVersion currentContract = contracts
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber).orElseThrow();
        if (!currentContract.getId().equals(contract.getId()) || currentContract.getRowVersion() != contractRowVersion) {
            throw new IllegalStateException("审阅期间合同已变化，请重新审阅");
        }
        int version = contractReviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(value -> value.getVersionNumber() + 1).orElse(1);
        ChapterContractReviewVersion review = ChapterContractReviewVersion.create(UUID.randomUUID(), projectId,
                chapterNumber, contract.getId(), contractRowVersion, version, provider.name(),
                normalize(request.instruction()), generated);
        return ChapterContractReviewResponse.from(contractReviews.saveAndFlush(review));
    }

    /**
     * 保存作者对审阅问题的处理并确认报告；来源合同或最新报告变化时拒绝使用旧结果。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional
    public ChapterContractReviewResponse approveContractReview(UUID projectId, UUID id, long expected,
            ChapterContractReviewContent content) {
        contexts.requireOwnedProject(projectId);
        ChapterContractReviewVersion review = contractReviews.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new WritingResourceNotFoundException("合同审阅", id));
        check(review.getRowVersion(), expected);
        ChapterContractVersion contract = requireContract(projectId, review.getSourceContractVersionId());
        requireCurrentContractReview(projectId, contract, review);
        if (content != null) review.revise(content);
        review.approve();
        return ChapterContractReviewResponse.from(contractReviews.saveAndFlush(review));
    }

    private void requireCurrentContractReview(UUID projectId, ChapterContractVersion contract,
            ChapterContractReviewVersion review) {
        if (!contract.getSourceOutlineVersionId().equals(contexts.requireOwnedProject(projectId).getCurrentOutlineVersionId())) {
            throw new IllegalStateException("章节合同来自旧大纲，请重新生成并审阅");
        }
        ChapterContractVersion latest = contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(
                projectId, contract.getChapterNumber()).orElseThrow();
        ChapterContractReviewVersion latestReview = contractReviews
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, contract.getChapterNumber())
                .orElseThrow(() -> new IllegalStateException("请先审阅当前章节合同"));
        if (!latest.getId().equals(contract.getId()) || !latestReview.getId().equals(review.getId())
                || !review.getSourceContractVersionId().equals(contract.getId())
                || review.getSourceContractRowVersion() != contract.getRowVersion()) {
            throw new IllegalStateException("合同或审阅已更新，请重新审阅当前保存版本");
        }
    }

    /**
     * 按合同当前状态和行版本保存作者编辑；修改后的合同不能沿用不匹配的旧审阅批准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional
    public ChapterContractResponse updateContract(UUID projectId, UUID id, long expected, ChapterContractContent content) {
        contexts.requireOwnedProject(projectId);
        ChapterContractVersion value = requireContract(projectId, id);
        check(value.getRowVersion(), expected);
        value.revise(content);
        return ChapterContractResponse.from(contracts.saveAndFlush(value));
    }

    /**
     * 核对最新合同、最新已确认审阅及来源行版本后确认合同，为正式正文提供依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     */
    @Transactional
    public ChapterContractResponse approveContract(UUID projectId, UUID id, long expected) {
        contexts.requireOwnedProject(projectId);
        ChapterContractVersion value = requireContract(projectId, id);
        check(value.getRowVersion(), expected);
        ChapterContractReviewVersion review = contractReviews
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, value.getChapterNumber())
                .orElseThrow(() -> new IllegalStateException("请先审阅并确认当前章节合同"));
        requireCurrentContractReview(projectId, value, review);
        if (review.getStatus() != ReviewStatus.APPROVED) {
            throw new IllegalStateException("请先确认合同审阅结果");
        }
        value.approve();
        return ChapterContractResponse.from(contracts.saveAndFlush(value));
    }

    private ChapterContractVersion requireContract(UUID projectId, UUID id) {
        return contracts.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new WritingResourceNotFoundException("章节合同", id));
    }
}
