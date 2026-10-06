package com.novelagent.writing.application;

import static com.novelagent.writing.application.WritingChecks.check;
import static com.novelagent.writing.application.WritingChecks.normalize;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.canon.application.EntityCatalogService;
import com.novelagent.memory.application.ContextBudgetPlanner;
import com.novelagent.memory.application.MemoryBudgetPlan;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.planning.application.GenerationMode;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.ChapterReviewResponse;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.ReturnReviewRequest;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ChapterReviewVersion;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.domain.ReviewIssue;
import com.novelagent.writing.domain.ReviewStatus;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ChapterReviewVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChapterReviewService {
    private final WritingContextService contexts;
    private final ChapterContractVersionRepository contracts;
    private final ManuscriptVersionRepository manuscripts;
    private final ChapterReviewVersionRepository reviews;
    private final WritingGenerationWorkflow workflow;
    private final ContextBudgetPlanner budgetPlanner;
    private final EntityCatalogService entityCatalog;
    private final ManuscriptService manuscriptService;
    private final TransactionTemplate transactions;

    public ChapterReviewService(WritingContextService contexts, ChapterContractVersionRepository contracts,
            ManuscriptVersionRepository manuscripts, ChapterReviewVersionRepository reviews,
            WritingGenerationWorkflow workflow, ContextBudgetPlanner budgetPlanner, EntityCatalogService entityCatalog,
            ManuscriptService manuscriptService,
            @Qualifier("writingTransactionTemplate") TransactionTemplate transactions) {
        this.contexts = contexts;
        this.contracts = contracts;
        this.manuscripts = manuscripts;
        this.reviews = reviews;
        this.workflow = workflow;
        this.budgetPlanner = budgetPlanner;
        this.entityCatalog = entityCatalog;
        this.manuscriptService = manuscriptService;
        this.transactions = transactions;
    }

    public ManuscriptResponse returnReviewToWriting(UUID projectId, int chapterNumber, UUID reviewId,
            long expectedVersion, ReturnReviewRequest request) {
        contexts.requireOwnedProject(projectId);
        ChapterReviewVersion review = requireReview(projectId, reviewId);
        check(review.getRowVersion(), expectedVersion);
        if (review.getChapterNumber() != chapterNumber || review.getStatus() != ReviewStatus.DRAFT) {
            throw new IllegalArgumentException("只有本章待处理审稿可以打回正文");
        }
        ChapterReviewVersion latestReview = reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(
                projectId, chapterNumber).orElseThrow();
        if (!latestReview.getId().equals(reviewId)) throw new IllegalStateException("请从最新审稿打回正文");
        ManuscriptVersion source = manuscripts.findByIdAndProjectIdAndChapterNumber(
                review.getSourceManuscriptVersionId(), projectId, chapterNumber)
                .orElseThrow(() -> new IllegalArgumentException("审稿关联正文不存在"));
        ManuscriptVersion latest = manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(
                projectId, chapterNumber).orElseThrow();
        if (!latest.getId().equals(source.getId()) || source.getStatus() != ManuscriptStatus.AUTHOR_ACCEPTED) {
            throw new IllegalStateException("正文已有新版本，请重新审稿后再打回");
        }
        GenerationMode mode = request.mode() == null ? GenerationMode.REVISE : request.mode();
        String feedback = reviewFeedback(review, request.issueIds(), request.instruction(), mode);
        ManuscriptVersion draft = manuscriptService.prepareManuscript(projectId, chapterNumber,
                new GenerateWritingRequest(request.provider(), feedback, mode,
                        mode == GenerationMode.REVISE ? source.getId() : null, null));
        draft.linkReturnedReview(reviewId);
        return transactions.execute(status -> {
            ChapterReviewVersion current = requireReview(projectId, reviewId);
            check(current.getRowVersion(), expectedVersion);
            if (current.getStatus() != ReviewStatus.DRAFT) throw new IllegalStateException("审稿状态已变化，请刷新后重试");
            ManuscriptVersion currentLatest = manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(
                    projectId, chapterNumber).orElseThrow();
            if (!currentLatest.getId().equals(source.getId())) {
                throw new IllegalStateException("正文已有新版本，请刷新后重试");
            }
            current.returnForRewrite();
            ManuscriptVersion saved = manuscripts.saveAndFlush(draft);
            reviews.saveAndFlush(current);
            return ManuscriptResponse.from(saved, manuscriptService.renderContent(saved));
        });
    }

    private String reviewFeedback(ChapterReviewVersion review, List<String> issueIds, String instruction,
            GenerationMode mode) {
        if (issueIds == null || issueIds.isEmpty() || issueIds.size() > 20) {
            throw new IllegalArgumentException("请选择 1 至 20 条审稿问题");
        }
        Set<String> selected = new LinkedHashSet<>(issueIds);
        if (selected.size() != issueIds.size() || selected.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("审稿问题编号无效或重复");
        }
        if (instruction != null && instruction.length() > 2000) {
            throw new IllegalArgumentException("补充要求不能超过 2000 字");
        }
        StringBuilder feedback = new StringBuilder(mode == GenerationMode.REVISE
                ? "以审稿关联原稿为底稿，逐条解决以下问题，并在修改说明中对应说明；未涉及的内容尽量保留：\n"
                : "依据当前故事圣经、章节计划和章节合同重写整章，避免以下旧稿问题；本次不是局部修订，修改说明返回空数组：\n");
        for (String id : selected) {
            ReviewIssue issue = review.getContent().issues().stream()
                    .filter(item -> java.util.Objects.equals(item.id(), id)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("审稿问题不存在：" + id));
            if ((issue.description() == null || issue.description().isBlank())
                    && (issue.suggestion() == null || issue.suggestion().isBlank())) {
                throw new IllegalArgumentException("审稿问题缺少可执行的修改说明：" + id);
            }
            feedback.append("- [").append(issue.severity()).append('/').append(issue.category())
                    .append("] 问题：").append(issue.description())
                    .append("；原文证据：").append(issue.evidence())
                    .append("；修改建议：").append(issue.suggestion()).append('\n');
        }
        feedback.append("审稿来源版本：").append(review.getVersionNumber()).append("。不得只把问题标记为已处理。\n")
                .append("作者补充要求：").append(normalize(instruction) == null ? "无" : normalize(instruction));
        return feedback.toString();
    }

    @Transactional(readOnly = true)
    public Optional<ChapterReviewResponse> latestReview(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(ChapterReviewResponse::from);
    }

    public ChapterReviewResponse generateReview(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        WritingContextService.Context context = contexts.context(projectId, chapterNumber);
        WritingBasisSnapshot basis = WritingBasisSnapshot.capture(context);
        ManuscriptVersion manuscript = manuscripts.findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                projectId, chapterNumber, ManuscriptStatus.AUTHOR_ACCEPTED)
                .orElseThrow(() -> new IllegalArgumentException("请先由作者确认本章正文"));
        ChapterContractVersion contract = contracts.findByIdAndProjectId(manuscript.getSourceContractVersionId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("正文关联的章节合同不可用"));
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        MemoryBudgetPlan budget = budgetPlanner.plan(AgentStage.CHAPTER_REVIEW, provider,
                context.budgetInputs(context.bible().getContent(), contract.getContent(), manuscript.getContent(), request.instruction()));
        NovelMemoryContext recalled = contexts.recall(AgentStage.CHAPTER_REVIEW, context, request.instruction(), budget);
        ChapterReviewContent generated = workflow.generateReview(projectId, context.bible().getContent(), contract.getContent(),
                manuscript.getContent(), recalled, entityCatalog.forReview(projectId, contract.getContent()),
                provider, context.instructionWithPreparation(normalize(request.instruction())));
        basis.requireUnchanged(contexts.context(projectId, chapterNumber));
        int version = reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(value -> value.getVersionNumber() + 1).orElse(1);
        ChapterReviewVersion review = ChapterReviewVersion.create(UUID.randomUUID(), projectId, chapterNumber,
                manuscript.getId(), version, provider.name(), normalize(request.instruction()), generated);
        return ChapterReviewResponse.from(reviews.saveAndFlush(review));
    }

    @Transactional
    public ChapterReviewResponse updateReview(UUID projectId, UUID id, long expected, ChapterReviewContent content) {
        contexts.requireOwnedProject(projectId);
        ChapterReviewVersion value = requireReview(projectId, id);
        check(value.getRowVersion(), expected);
        value.revise(content);
        return ChapterReviewResponse.from(reviews.saveAndFlush(value));
    }

    @Transactional
    public ChapterReviewResponse approveReview(UUID projectId, UUID id, long expected, ChapterReviewContent content) {
        contexts.requireOwnedProject(projectId);
        ChapterReviewVersion value = requireReview(projectId, id);
        check(value.getRowVersion(), expected);
        if (content != null) value.revise(content);
        value.approve();
        return ChapterReviewResponse.from(reviews.saveAndFlush(value));
    }

    private ChapterReviewVersion requireReview(UUID projectId, UUID id) {
        return reviews.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new WritingResourceNotFoundException("章节审稿", id));
    }
}

