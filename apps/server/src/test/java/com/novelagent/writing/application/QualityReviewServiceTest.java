package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import com.novelagent.memory.application.ContextBudgetPlanner;
import com.novelagent.memory.application.NovelMemoryService;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.QualityDimension;
import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.QualityReviewVersion;
import com.novelagent.writing.domain.QualityScore;
import com.novelagent.writing.domain.ReviewIssue;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.ReviseQualityRequest.Scope;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptStatus;
import org.mockito.ArgumentCaptor;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class QualityReviewServiceTest {
    private QualityReviewVersion report(UUID project) {
        return report(project, "FLUENCY");
    }
    private QualityReviewVersion report(UUID project, String category) {
        var scores = Arrays.stream(QualityDimension.values()).map(d -> new QualityScore(d, null, "未评分")).toList();
        var issue = new ReviewIssue("Q1", "INFO", category, "重复标点", "。。", "复核标点", false);
        return QualityReviewVersion.create(project, 1, 1, UUID.randomUUID(), 0, UUID.randomUUID(), "a".repeat(64),
                "LOCAL_TEMPLATE", null, new QualityReviewContent("摘要", scores, List.of(issue)));
    }
    @Test void selectedFeedbackPreservesHardConstraintsAndRejectsUnknownOrDuplicateIds() {
        var report = report(UUID.randomUUID());
        assertThat(QualityReviewService.revisionInstruction(report, List.of("Q1"), null))
                .contains(report.getId().toString(), "保持事件", "不得新增设定", "原文：。。", "changeSummary");
        assertThatThrownBy(() -> QualityReviewService.revisionInstruction(report, List.of("missing"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QualityReviewService.revisionInstruction(report, List.of("Q1", "Q1"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QualityReviewService.revisionInstruction(report, List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void staleReportStopsBeforeAnyModelOrManuscriptCall() {
        UUID project = UUID.randomUUID();
        var report = report(project);
        var store = mock(QualityReviewStore.class);
        var workflow = mock(WritingGenerationWorkflow.class);
        var writing = mock(ManuscriptService.class);
        when(store.get(project, 1, report.getId())).thenReturn(report);
        doThrow(new IllegalStateException("已过期")).when(store).requireCurrent(report);
        var service = new QualityReviewService(store, workflow, writing, mock(NovelMemoryService.class), mock(ContextBudgetPlanner.class));
        assertThatThrownBy(() -> service.revise(project, 1, report.getId(), List.of("Q1"), ModelProvider.LOCAL_TEMPLATE, null))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(workflow, writing);
    }

    @Test void structuralProblemsRequireExplicitScopeEvenWithAuthorInstructions() {
        for (String category : List.of("LOGIC", "SCENE")) {
            var report = report(UUID.randomUUID(), category);
            assertThatThrownBy(() -> QualityReviewService.revisionInstruction(report, List.of("Q1"), "请重排整个故事"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SCENE_STRUCTURE");
            String feedback = QualityReviewService.revisionInstruction(report, List.of("Q1"), "新增人物知识", Scope.SCENE_STRUCTURE);
            assertThat(feedback).contains("授权范围：SCENE_STRUCTURE", "仅允许", "事件实际发生顺序", "不授权新故事事实",
                    "知识边界", "关系和退出状态", "选中问题以外不修订", "不能扩大授权", "不能声称已自动验证");
            assertThat(feedback.substring(feedback.indexOf("最终执行边界"))).doesNotContain("新增人物知识");
        }
        assertThat(QualityReviewService.revisionInstruction(report(UUID.randomUUID()), List.of("Q1"), "重排场景", null))
                .contains("授权范围：EXPRESSION_ONLY", "不调整场景结构", "冲突要求不执行");
    }

    @Test void invalidSelectionAndInstructionStopBeforePreparingCandidates() {
        var store = mock(QualityReviewStore.class);
        var writing = mock(ManuscriptService.class);
        var report = report(UUID.randomUUID(), "SCENE");
        when(store.get(report.getProjectId(), 1, report.getId())).thenReturn(report);
        var service = new QualityReviewService(store, mock(WritingGenerationWorkflow.class), writing,
                mock(NovelMemoryService.class), mock(ContextBudgetPlanner.class));
        for (List<String> ids : List.of(List.of("Q1"), List.of("missing"), List.of("Q1", "Q1"), List.of(" "))) {
            assertThatThrownBy(() -> service.revise(report.getProjectId(), 1, report.getId(), ids, ModelProvider.DEEPSEEK, null))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.revise(report.getProjectId(), 1, report.getId(), List.of("Q1"), ModelProvider.DEEPSEEK,
                "a".repeat(2001), Scope.SCENE_STRUCTURE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> QualityReviewService.revisionInstruction(report, Arrays.asList((String) null), null, Scope.SCENE_STRUCTURE))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(writing);
        verify(store, never()).saveRevision(any(), any());
    }

    @Test void bothScopesSaveNewDraftsWithSourceAndActualChangeSummaryAndNoAuthorApproval() {
        for (Scope scope : Scope.values()) {
            var report = report(UUID.randomUUID(), scope == Scope.EXPRESSION_ONLY ? "FLUENCY" : "SCENE");
            var store = mock(QualityReviewStore.class);
            var writing = mock(ManuscriptService.class);
            when(store.get(report.getProjectId(), 1, report.getId())).thenReturn(report);
            var draft = ManuscriptVersion.create(UUID.randomUUID(), report.getProjectId(), UUID.randomUUID(), 1, 2,
                    "DEEPSEEK", null, report.getSourceManuscriptId(), new ManuscriptContent("纸条", "正文。", "摘要", List.of()), List.of("Q1：调整标点或组织"));
            var feedback = ArgumentCaptor.forClass(String.class);
            when(writing.prepareQualityRevision(eq(report.getProjectId()), eq(1), eq(report.getSourceManuscriptId()),
                    eq(report.getSourceManuscriptRowVersion()), eq(ModelProvider.DEEPSEEK), feedback.capture())).thenReturn(draft);
            when(store.saveRevision(report, draft)).thenReturn(ManuscriptResponse.from(draft));
            var service = new QualityReviewService(store, mock(WritingGenerationWorkflow.class), writing,
                    mock(NovelMemoryService.class), mock(ContextBudgetPlanner.class));
            var saved = service.revise(report.getProjectId(), 1, report.getId(), List.of("Q1"), ModelProvider.DEEPSEEK, "作者要求", scope);
            assertThat(saved.status()).isEqualTo(ManuscriptStatus.DRAFT);
            assertThat(saved.id()).isNotEqualTo(report.getSourceManuscriptId());
            assertThat(saved.baseManuscriptVersionId()).isEqualTo(report.getSourceManuscriptId());
            assertThat(saved.changeSummary()).containsExactly("Q1：调整标点或组织");
            assertThat(feedback.getValue()).contains("授权范围：" + scope, "Q1", "待作者审阅");
            verify(store).requireCurrent(report);
            verify(store).saveRevision(report, draft);
        }
    }

    @Test void revisionFailureCannotSaveOrReplaceOriginal() {
        var report = report(UUID.randomUUID());
        var store = mock(QualityReviewStore.class);
        var writing = mock(ManuscriptService.class);
        when(store.get(report.getProjectId(), 1, report.getId())).thenReturn(report);
        when(writing.prepareQualityRevision(any(), eq(1), any(), eq(0L), any(), any())).thenThrow(new IllegalStateException("模型失败"));
        var service = new QualityReviewService(store, mock(WritingGenerationWorkflow.class), writing,
                mock(NovelMemoryService.class), mock(ContextBudgetPlanner.class));
        assertThatThrownBy(() -> service.revise(report.getProjectId(), 1, report.getId(), List.of("Q1"), ModelProvider.DEEPSEEK, null))
                .hasMessage("模型失败");
        verify(store, never()).saveRevision(any(), any());
    }
}
