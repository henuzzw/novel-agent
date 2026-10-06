package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.EntityCatalogService;
import com.novelagent.memory.application.ContextBudgetPlanner;
import com.novelagent.memory.application.MemoryBudgetPlan;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.memory.application.NovelMemoryService;
import com.novelagent.planning.application.GenerationMode;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.writing.api.ReturnReviewRequest;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.domain.ChapterReviewVersion;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.ReviewIssue;
import com.novelagent.writing.domain.ReviewStatus;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ChapterReviewVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class WritingServiceManuscriptVersionTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID outlineId = UUID.randomUUID();
    private final UUID bibleId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final OutlineVersionRepository outlines = mock(OutlineVersionRepository.class);
    private final StoryBibleVersionRepository bibles = mock(StoryBibleVersionRepository.class);
    private final ChapterContractVersionRepository contracts = mock(ChapterContractVersionRepository.class);
    private final ManuscriptVersionRepository manuscripts = mock(ManuscriptVersionRepository.class);
    private final ChapterReviewVersionRepository reviews = mock(ChapterReviewVersionRepository.class);
    private final WritingGenerationWorkflow workflow = mock(WritingGenerationWorkflow.class);
    private final CurrentActorProvider actor = mock(CurrentActorProvider.class);
    private final NovelMemoryService memory = mock(NovelMemoryService.class);
    private final ContextBudgetPlanner budgetPlanner = mock(ContextBudgetPlanner.class);
    private final EntityCatalogService entityCatalog = mock(EntityCatalogService.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);
    private final WritingContextService contexts = new WritingContextService(new ProjectAccessService(projects, actor),
            outlines, bibles, memory);
    private final ManuscriptService manuscriptService = new ManuscriptService(contexts, contracts, manuscripts,
            workflow, budgetPlanner, names, mock(WritingStyleService.class));
    private final WritingService service = new WritingService(mock(ChapterContractService.class), manuscriptService,
            new ChapterReviewService(contexts, contracts, manuscripts, reviews, workflow, budgetPlanner, entityCatalog,
                    manuscriptService, transactions));
    private final ManuscriptContent generated = content("调整后的正文");

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        NovelProject project = mock(NovelProject.class);
        OutlineVersion outline = mock(OutlineVersion.class);
        StoryBibleVersion bible = mock(StoryBibleVersion.class);
        ChapterContractVersion contract = mock(ChapterContractVersion.class);
        ChapterPlan chapter = new ChapterPlan(1, "第一章", "主角", "目标", "事件", "揭示", "钩子", 2000, 3000);
        OutlineArc arc = new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果",
                2000, 3000, List.of(chapter));
        StoryBibleContent bibleContent = new StoryBibleContent("故事", "主题", "世界", List.of(), "主角",
                "弧光", List.of(), List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
        ChapterContractContent contractContent = new ChapterContractContent("第一章", "主角", "目标", "当天",
                List.of(), List.of(), List.of(), List.of(), "退出状态", List.of(), "钩子", 2000, 3000);
        MemoryBudgetPlan budget = new MemoryBudgetPlan(AgentStage.MANUSCRIPT, ModelProvider.LOCAL_TEMPLATE,
                32000, 1000, 5000, 2000, 8000, 4000, 8000, 3, 3);
        NovelMemoryContext recalled = mock(NovelMemoryContext.class);

        when(actor.currentUserId()).thenReturn(userId);
        when(project.getOwnerId()).thenReturn(userId);
        when(project.getId()).thenReturn(projectId);
        when(project.getCurrentOutlineVersionId()).thenReturn(outlineId);
        when(project.getCurrentBibleVersionId()).thenReturn(bibleId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(outline.getId()).thenReturn(outlineId);
        when(outline.getProjectId()).thenReturn(projectId);
        when(outline.getStatus()).thenReturn(OutlineStatus.PUBLISHED);
        when(outline.getSourceBibleVersionId()).thenReturn(bibleId);
        when(outline.getContent()).thenReturn(new OutlineContent("小说", "前提", "结构", "节奏",
                2000, 3000, List.of(arc)));
        when(outlines.findByIdAndProjectId(outlineId, projectId)).thenReturn(Optional.of(outline));
        when(bible.getContent()).thenReturn(bibleContent);
        when(bible.getStatus()).thenReturn(com.novelagent.planning.domain.StoryBibleStatus.PUBLISHED);
        when(bibles.findByIdAndProjectId(bibleId, projectId)).thenReturn(Optional.of(bible));
        when(contract.getId()).thenReturn(contractId);
        when(contract.getSourceOutlineVersionId()).thenReturn(outlineId);
        when(contract.getContent()).thenReturn(contractContent);
        when(contracts.findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                projectId, 1, ChapterContractStatus.APPROVED)).thenReturn(Optional.of(contract));
        when(budgetPlanner.plan(eq(AgentStage.MANUSCRIPT), eq(ModelProvider.LOCAL_TEMPLATE),
                any(), any(), any(), any(), any(), any(), any())).thenReturn(budget);
        when(memory.recall(eq(AgentStage.MANUSCRIPT), eq(projectId), eq(1), any(Long.class),
                any(), eq(budget))).thenReturn(recalled);
        when(workflow.generateManuscript(eq(projectId), any(), any(), any(), any(), eq(recalled),
                any(), eq(ModelProvider.LOCAL_TEMPLATE), any()))
                .thenReturn(new GeneratedManuscript(generated, List.of("调整了对话")));
        when(names.tokenize(projectId, generated)).thenReturn(generated);
        when(names.render(eq(projectId), any(ManuscriptContent.class))).thenAnswer(call -> call.getArgument(1));
        when(names.render(eq(projectId), any(String.class))).thenAnswer(call -> call.getArgument(1));
        when(manuscripts.saveAndFlush(any(ManuscriptVersion.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void revisesSelectedOlderVersionAndRecordsItsSource() {
        ManuscriptVersion older = manuscript(2, "喜欢的旧稿");
        ManuscriptVersion latest = manuscript(3, "不满意的新稿");
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(latest));
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(older.getId(), projectId, 1))
                .thenReturn(Optional.of(older));

        var result = service.generateManuscript(projectId, 1, request(GenerationMode.REVISE, older.getId()));

        assertThat(result.versionNumber()).isEqualTo(4);
        assertThat(result.baseManuscriptVersionId()).isEqualTo(older.getId());
        assertThat(result.content()).isEqualTo(generated);
        verify(workflow).generateManuscript(eq(projectId), any(), any(), any(), any(), any(),
                eq(older.getContent()), eq(ModelProvider.LOCAL_TEMPLATE), eq("微调"));
    }

    @Test
    void defaultsToLatestSavedVersion() {
        ManuscriptVersion latest = manuscript(3, "最新保存稿");
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(latest));

        when(manuscripts.findByIdAndProjectIdAndChapterNumber(latest.getId(), projectId, 1))
                .thenReturn(Optional.of(latest));
        var result = service.generateManuscript(projectId, 1, request(GenerationMode.REVISE, null));

        assertThat(result.baseManuscriptVersionId()).isEqualTo(latest.getId());
        verify(workflow).generateManuscript(eq(projectId), any(), any(), any(), any(), any(),
                eq(latest.getContent()), eq(ModelProvider.LOCAL_TEMPLATE), eq("微调"));
    }

    @Test
    void rejectsCrossChapterBaseBeforeCallingModel() {
        UUID wrongChapterVersionId = UUID.randomUUID();

        assertThatThrownBy(() -> service.generateManuscript(projectId, 1,
                request(GenerationMode.REVISE, wrongChapterVersionId)))
                .isInstanceOf(WritingResourceNotFoundException.class);
        verify(workflow, never()).generateManuscript(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void rejectsContractChangedDuringGenerationWithoutSavingDraft() {
        ChapterContractVersion contract = contracts
                .findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                        projectId, 1, ChapterContractStatus.APPROVED).orElseThrow();
        when(workflow.generateManuscript(eq(projectId), any(), any(), any(), any(), any(),
                any(), eq(ModelProvider.LOCAL_TEMPLATE), any())).thenAnswer(call -> {
                    when(contract.getRowVersion()).thenReturn(1L);
                    return new GeneratedManuscript(generated, List.of());
                });
        assertThatThrownBy(() -> service.generateManuscript(projectId, 1,
                request(GenerationMode.REGENERATE, null))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("章节合同或写作风格");
        verify(manuscripts, never()).saveAndFlush(any());
    }

    @Test
    void rejectsBaseChangedDuringGenerationWithoutSavingDraft() {
        ManuscriptVersion base = manuscript(1, "原文");
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(base));
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(base.getId(), projectId, 1))
                .thenReturn(Optional.of(base));
        when(workflow.generateManuscript(eq(projectId), any(), any(), any(), any(), any(),
                any(), eq(ModelProvider.LOCAL_TEMPLATE), any())).thenAnswer(call -> {
                    base.revise(content("作者在生成期间改稿"));
                    return new GeneratedManuscript(generated, List.of());
                });
        assertThatThrownBy(() -> service.generateManuscript(projectId, 1,
                request(GenerationMode.REVISE, base.getId()))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("基准正文");
        verify(manuscripts, never()).saveAndFlush(any());
    }

    @Test
    void rejectsBaseWhenRegenerating() {
        assertThatThrownBy(() -> service.generateManuscript(projectId, 1,
                request(GenerationMode.REGENERATE, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能指定基准");
        verify(workflow, never()).generateManuscript(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void listsAndReadsVersionsWithinTheChapter() {
        ManuscriptVersion older = manuscript(2, "旧稿");
        ManuscriptVersion latest = manuscript(3, "新稿");
        when(manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(List.of(latest, older));
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(older.getId(), projectId, 1))
                .thenReturn(Optional.of(older));

        assertThat(service.manuscriptVersions(projectId, 1)).extracting("versionNumber")
                .containsExactly(3, 2);
        assertThat(service.manuscriptVersion(projectId, 1, older.getId()).content().body())
                .isEqualTo("旧稿");
        assertThatThrownBy(() -> service.manuscriptVersion(projectId, 2, older.getId()))
                .isInstanceOf(WritingResourceNotFoundException.class);
    }

    @Test
    void copiesAcceptedManuscriptIntoAnEditableDraftWithoutCallingModel() {
        ManuscriptVersion accepted = manuscript(3, "已确认的原文");
        accepted.accept();
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(accepted.getId(), projectId, 1))
                .thenReturn(Optional.of(accepted));
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(accepted));

        var revision = service.createManuscriptRevision(projectId, 1, accepted.getId(), accepted.getRowVersion());

        assertThat(revision.versionNumber()).isEqualTo(4);
        assertThat(revision.baseManuscriptVersionId()).isEqualTo(accepted.getId());
        assertThat(revision.sourceContractVersionId()).isEqualTo(contractId);
        assertThat(revision.content().body()).isEqualTo("已确认的原文");
        assertThat(revision.status().name()).isEqualTo("DRAFT");
        assertThat(revision.generatorType()).isEqualTo("AUTHOR_EDIT");
        assertThat(accepted.getStatus().name()).isEqualTo("AUTHOR_ACCEPTED");
        verify(workflow, never()).generateManuscript(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsManualRevisionFromAnUnconfirmedOrSupersededVersion() {
        ManuscriptVersion draft = manuscript(2, "未确认");
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(draft.getId(), projectId, 1))
                .thenReturn(Optional.of(draft));
        assertThatThrownBy(() -> service.createManuscriptRevision(projectId, 1, draft.getId(), 0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("已确认");

        draft.accept();
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(manuscript(3, "更新稿")));
        assertThatThrownBy(() -> service.createManuscriptRevision(projectId, 1, draft.getId(), 0))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("请刷新");
        verify(manuscripts, never()).saveAndFlush(any(ManuscriptVersion.class));
    }

    @Test
    void rejectsWrongChapterAndStalePreconditionForManualRevision() {
        ManuscriptVersion accepted = manuscript(2, "已确认");
        accepted.accept();
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(accepted.getId(), projectId, 1))
                .thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.createManuscriptRevision(projectId, 2, accepted.getId(), 0))
                .isInstanceOf(WritingResourceNotFoundException.class);
        assertThatThrownBy(() -> service.createManuscriptRevision(projectId, 1, accepted.getId(), 1))
                .isInstanceOf(ResourceVersionConflictException.class);
        verify(manuscripts, never()).saveAndFlush(any(ManuscriptVersion.class));
    }

    @Test
    void returnsSelectedReviewIssuesIntoANewDraftAndKeepsOriginalCanonUntouched() {
        ManuscriptVersion source = manuscript(3, "已确认的原文");
        source.accept();
        ChapterReviewVersion review = review(source);
        when(reviews.findByIdAndProjectId(review.getId(), projectId)).thenReturn(Optional.of(review));
        when(reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(review));
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(source.getId(), projectId, 1))
                .thenReturn(Optional.of(source));
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(source));
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));

        var result = service.returnReviewToWriting(projectId, 1, review.getId(), 0,
                new ReturnReviewRequest(ModelProvider.LOCAL_TEMPLATE, GenerationMode.REVISE,
                        List.of("I1"), "保持对话自然"));

        assertThat(result.status().name()).isEqualTo("DRAFT");
        assertThat(result.versionNumber()).isEqualTo(4);
        assertThat(result.baseManuscriptVersionId()).isEqualTo(source.getId());
        assertThat(result.sourceReviewVersionId()).isEqualTo(review.getId());
        assertThat(source.getStatus()).isEqualTo(com.novelagent.writing.domain.ManuscriptStatus.AUTHOR_ACCEPTED);
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.RETURNED);
        org.mockito.ArgumentCaptor<String> feedback = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(workflow).generateManuscript(eq(projectId), any(), any(), any(), any(), any(),
                eq(source.getContent()), eq(ModelProvider.LOCAL_TEMPLATE), feedback.capture());
        assertThat(feedback.getValue()).contains("人物连续性", "原文证据", "成绩不错", "保持对话自然");
    }

    @Test
    void rejectsReviewWithoutSelectedIssuesBeforeCallingModel() {
        ManuscriptVersion source = manuscript(3, "已确认的原文");
        source.accept();
        ChapterReviewVersion review = review(source);
        when(reviews.findByIdAndProjectId(review.getId(), projectId)).thenReturn(Optional.of(review));
        when(reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(review));
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(source.getId(), projectId, 1))
                .thenReturn(Optional.of(source));
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(source));

        assertThatThrownBy(() -> service.returnReviewToWriting(projectId, 1, review.getId(), 0,
                new ReturnReviewRequest(ModelProvider.LOCAL_TEMPLATE, GenerationMode.REVISE,
                        List.of(), null))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("请选择");
        verify(workflow, never()).generateManuscript(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.DRAFT);
    }

    @Test
    void modelFailureDoesNotReturnReviewOrCreateDraft() {
        ManuscriptVersion source = manuscript(3, "已确认的原文");
        source.accept();
        ChapterReviewVersion review = review(source);
        when(reviews.findByIdAndProjectId(review.getId(), projectId)).thenReturn(Optional.of(review));
        when(reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(review));
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(source.getId(), projectId, 1))
                .thenReturn(Optional.of(source));
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(source));
        when(workflow.generateManuscript(eq(projectId), any(), any(), any(), any(), any(), any(),
                eq(ModelProvider.LOCAL_TEMPLATE), any())).thenThrow(new IllegalStateException("模型失败"));

        assertThatThrownBy(() -> service.returnReviewToWriting(projectId, 1, review.getId(), 0,
                new ReturnReviewRequest(ModelProvider.LOCAL_TEMPLATE, GenerationMode.REVISE,
                        List.of("I1"), null))).hasMessageContaining("模型失败");
        assertThat(review.getStatus()).isEqualTo(ReviewStatus.DRAFT);
        verify(manuscripts, never()).saveAndFlush(any(ManuscriptVersion.class));
        verify(transactions, never()).execute(any());
    }

    @Test
    void regeneratingReturnedReviewDoesNotUseOldManuscriptAsBase() {
        ManuscriptVersion source = manuscript(3, "已确认的原文");
        source.accept();
        ChapterReviewVersion review = review(source);
        when(reviews.findByIdAndProjectId(review.getId(), projectId)).thenReturn(Optional.of(review));
        when(reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(review));
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(source.getId(), projectId, 1))
                .thenReturn(Optional.of(source));
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(source));
        when(transactions.execute(any())).thenAnswer(call ->
                ((TransactionCallback<?>) call.getArgument(0)).doInTransaction(null));

        var result = service.returnReviewToWriting(projectId, 1, review.getId(), 0,
                new ReturnReviewRequest(ModelProvider.LOCAL_TEMPLATE, GenerationMode.REGENERATE,
                        List.of("I1"), null));

        assertThat(result.baseManuscriptVersionId()).isNull();
        assertThat(result.sourceReviewVersionId()).isEqualTo(review.getId());
        org.mockito.ArgumentCaptor<String> feedback = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(workflow).generateManuscript(eq(projectId), any(), any(), any(), any(), any(),
                org.mockito.ArgumentMatchers.isNull(), eq(ModelProvider.LOCAL_TEMPLATE), feedback.capture());
        assertThat(feedback.getValue()).contains("重写整章", "修改说明返回空数组");
    }

    @Test
    void rejectsStaleReviewBeforeCallingModel() {
        ManuscriptVersion source = manuscript(3, "已确认的原文");
        source.accept();
        ChapterReviewVersion review = review(source);
        when(reviews.findByIdAndProjectId(review.getId(), projectId)).thenReturn(Optional.of(review));
        when(reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(review));
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(source.getId(), projectId, 1))
                .thenReturn(Optional.of(source));
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(manuscript(4, "已有新稿")));

        assertThatThrownBy(() -> service.returnReviewToWriting(projectId, 1, review.getId(), 0,
                new ReturnReviewRequest(ModelProvider.LOCAL_TEMPLATE, GenerationMode.REVISE,
                        List.of("I1"), null))).hasMessageContaining("正文已有新版本");
        verify(workflow, never()).generateManuscript(any(), any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void approvalPersistsCurrentFactDecisionsWithoutSeparateSave() {
        UUID manuscriptId = UUID.randomUUID();
        FactProposal pending = new FactProposal("F1", "EVENT", "许言川", "得知", "名次", "名单", FactDecision.PENDING);
        ChapterReviewVersion review = ChapterReviewVersion.create(UUID.randomUUID(), projectId, 2,
                manuscriptId, 4, "LOCAL_CODEX", null,
                new ChapterReviewContent("无问题", List.of(), List.of(pending)));
        when(reviews.findByIdAndProjectId(review.getId(), projectId)).thenReturn(Optional.of(review));
        when(reviews.saveAndFlush(review)).thenReturn(review);
        ChapterReviewContent current = new ChapterReviewContent("无问题", List.of(), List.of(
                new FactProposal("F1", "EVENT", "许言川", "得知", "名次", "名单", FactDecision.ACCEPTED)));

        var result = service.approveReview(projectId, review.getId(), 0, current);

        assertThat(result.status()).isEqualTo(ReviewStatus.APPROVED);
        assertThat(review.getContent()).isEqualTo(current);
        verify(reviews).saveAndFlush(review);
    }

    private ChapterReviewVersion review(ManuscriptVersion source) {
        return ChapterReviewVersion.create(UUID.randomUUID(), projectId, 1, source.getId(),
                2, "LOCAL_TEMPLATE", null, new ChapterReviewContent("需要修改", List.of(
                        new ReviewIssue("I1", "WARNING", "人物连续性", "成绩不符",
                                "成绩不错", "改为成绩偏下游", false)), List.of()));
    }

    private GenerateWritingRequest request(GenerationMode mode, UUID baseId) {
        return new GenerateWritingRequest(ModelProvider.LOCAL_TEMPLATE, "微调", mode, baseId, null);
    }

    private ManuscriptVersion manuscript(int number, String body) {
        return ManuscriptVersion.create(UUID.randomUUID(), projectId, contractId, 1, number,
                "LOCAL_TEMPLATE", null, content(body));
    }

    private ManuscriptContent content(String body) {
        return new ManuscriptContent("第一章", body, "摘要", List.of());
    }
}
