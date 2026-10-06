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
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ChapterContractReviewContent;
import com.novelagent.writing.domain.ChapterContractReviewVersion;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ChapterContractReviewVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WritingServiceContractVersionTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID outlineId = UUID.randomUUID();
    private final UUID bibleId = UUID.randomUUID();
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final OutlineVersionRepository outlines = mock(OutlineVersionRepository.class);
    private final StoryBibleVersionRepository bibles = mock(StoryBibleVersionRepository.class);
    private final ChapterContractVersionRepository contracts = mock(ChapterContractVersionRepository.class);
    private final ChapterContractReviewVersionRepository contractReviews = mock(ChapterContractReviewVersionRepository.class);
    private final WritingGenerationWorkflow workflow = mock(WritingGenerationWorkflow.class);
    private final CurrentActorProvider actor = mock(CurrentActorProvider.class);
    private final NovelMemoryService memory = mock(NovelMemoryService.class);
    private final ContextBudgetPlanner budgetPlanner = mock(ContextBudgetPlanner.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final WritingContextService contexts = new WritingContextService(new ProjectAccessService(projects, actor),
            outlines, bibles, memory);
    private final WritingService service = new WritingService(new ChapterContractService(contexts, contracts,
            contractReviews, workflow, budgetPlanner, names), mock(ManuscriptService.class), mock(ChapterReviewService.class));
    private final ChapterContractContent generated = content("调整后的合同");

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        NovelProject project = mock(NovelProject.class);
        OutlineVersion outline = mock(OutlineVersion.class);
        StoryBibleVersion bible = mock(StoryBibleVersion.class);
        ChapterPlan chapter = new ChapterPlan(1, "第一章", "主角", "目标", "事件", "揭示", "钩子", 2000, 3000);
        OutlineArc arc = new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果",
                2000, 3000, List.of(chapter));
        StoryBibleContent bibleContent = new StoryBibleContent("故事", "主题", "世界", List.of(), "主角",
                "弧光", List.of(), List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of());
        MemoryBudgetPlan budget = new MemoryBudgetPlan(AgentStage.CHAPTER_CONTRACT, ModelProvider.LOCAL_TEMPLATE,
                32000, 1000, 3000, 2000, 8000, 4000, 8000, 3, 3);
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
        when(budgetPlanner.plan(eq(AgentStage.CHAPTER_CONTRACT), eq(ModelProvider.LOCAL_TEMPLATE),
                any(), any(), any(), any(), any())).thenReturn(budget);
        when(memory.recall(eq(AgentStage.CHAPTER_CONTRACT), eq(projectId), eq(1), any(Long.class),
                any(), eq(budget))).thenReturn(recalled);
        when(workflow.generateContract(eq(projectId), any(), any(), any(), eq(recalled), any(),
                eq(ModelProvider.LOCAL_TEMPLATE), eq("微调"))).thenReturn(generated);
        when(names.render(eq(projectId), any(String.class))).thenAnswer(call -> call.getArgument(1));
        when(contracts.saveAndFlush(any(ChapterContractVersion.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void cannotApproveContractWithoutIndependentReview() {
        ChapterContractVersion draft = version(1, "待审合同");
        when(contracts.findByIdAndProjectId(draft.getId(), projectId)).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.approveContract(projectId, draft.getId(), draft.getRowVersion()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("审阅");
        assertThat(draft.getStatus()).isEqualTo(ChapterContractStatus.DRAFT);
    }

    @Test
    void staleContractReviewCannotApproveContract() {
        ChapterContractVersion draft = version(1, "待审合同");
        ChapterContractReviewVersion review = approvedReview(draft, draft.getRowVersion() + 1);
        when(contracts.findByIdAndProjectId(draft.getId(), projectId)).thenReturn(Optional.of(draft));
        when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(draft));
        when(contractReviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(review));

        assertThatThrownBy(() -> service.approveContract(projectId, draft.getId(), draft.getRowVersion()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("重新审阅");
        assertThat(draft.getStatus()).isEqualTo(ChapterContractStatus.DRAFT);
    }

    @Test
    void currentApprovedContractReviewAllowsContractConfirmation() {
        ChapterContractVersion draft = version(1, "已审合同");
        ChapterContractReviewVersion review = approvedReview(draft, draft.getRowVersion());
        when(contracts.findByIdAndProjectId(draft.getId(), projectId)).thenReturn(Optional.of(draft));
        when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(draft));
        when(contractReviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(review));

        service.approveContract(projectId, draft.getId(), draft.getRowVersion());

        assertThat(draft.getStatus()).isEqualTo(ChapterContractStatus.APPROVED);
    }

    private ChapterContractReviewVersion approvedReview(ChapterContractVersion contract, long sourceRowVersion) {
        ChapterContractReviewVersion review = ChapterContractReviewVersion.create(UUID.randomUUID(), projectId, 1,
                contract.getId(), sourceRowVersion, 1, "LOCAL_TEMPLATE", null,
                new ChapterContractReviewContent("审阅通过", List.of()));
        review.approve();
        return review;
    }

    @Test
    void revisesSelectedOlderContractAndRecordsTheBase() {
        ChapterContractVersion older = version(2, "喜欢的旧合同");
        ChapterContractVersion latest = version(3, "不满意的新合同");
        when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(latest));
        when(contracts.findByIdAndProjectIdAndChapterNumber(older.getId(), projectId, 1))
                .thenReturn(Optional.of(older));

        var result = service.generateContract(projectId, 1, request(GenerationMode.REVISE, older.getId()));

        assertThat(result.versionNumber()).isEqualTo(4);
        assertThat(result.baseContractVersionId()).isEqualTo(older.getId());
        assertThat(result.content()).isEqualTo(generated);
        verify(workflow).generateContract(eq(projectId), any(), any(), any(), any(),
                eq(older.getContent()), eq(ModelProvider.LOCAL_TEMPLATE), eq("微调"));
        verify(budgetPlanner).plan(eq(AgentStage.CHAPTER_CONTRACT), eq(ModelProvider.LOCAL_TEMPLATE),
                any(), any(), any(), eq(older.getContent()), eq("微调"));
    }

    @Test
    void defaultsToLatestContractWhenNoBaseSelected() {
        ChapterContractVersion latest = version(3, "最新合同");
        when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(latest));

        var result = service.generateContract(projectId, 1, request(GenerationMode.REVISE, null));

        assertThat(result.baseContractVersionId()).isEqualTo(latest.getId());
        verify(workflow).generateContract(eq(projectId), any(), any(), any(), any(),
                eq(latest.getContent()), eq(ModelProvider.LOCAL_TEMPLATE), eq("微调"));
    }

    @Test
    void rejectsAnotherChapterAndRegenerateWithBaseBeforeModelCall() {
        UUID unavailableId = UUID.randomUUID();
        assertThatThrownBy(() -> service.generateContract(projectId, 1,
                request(GenerationMode.REVISE, unavailableId)))
                .isInstanceOf(WritingResourceNotFoundException.class);
        assertThatThrownBy(() -> service.generateContract(projectId, 1,
                request(GenerationMode.REGENERATE, unavailableId)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能指定基准");
        verify(workflow, never()).generateContract(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void listsAndReadsOnlyTheRequestedChaptersVersions() {
        ChapterContractVersion older = version(2, "旧合同");
        ChapterContractVersion latest = version(3, "新合同");
        when(contracts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(List.of(latest, older));
        when(contracts.findByIdAndProjectIdAndChapterNumber(older.getId(), projectId, 1))
                .thenReturn(Optional.of(older));

        assertThat(service.contractVersions(projectId, 1)).extracting("versionNumber").containsExactly(3, 2);
        assertThat(service.contractVersion(projectId, 1, older.getId()).content().chapterTitle())
                .isEqualTo("旧合同");
        assertThatThrownBy(() -> service.contractVersion(projectId, 2, older.getId()))
                .isInstanceOf(WritingResourceNotFoundException.class);
    }

    private GenerateWritingRequest request(GenerationMode mode, UUID baseId) {
        return new GenerateWritingRequest(ModelProvider.LOCAL_TEMPLATE, "微调", mode, null, baseId);
    }

    private ChapterContractVersion version(int number, String title) {
        return ChapterContractVersion.create(UUID.randomUUID(), projectId, outlineId, 1, number,
                "LOCAL_TEMPLATE", null, content(title));
    }

    private ChapterContractContent content(String title) {
        return new ChapterContractContent(title, "主角", "目标", "当天", List.of("教室"),
                List.of("节拍"), List.of("揭示"), List.of("禁止"), "退出状态", List.of("伏笔"), "钩子", 2000, 3000);
    }
}
