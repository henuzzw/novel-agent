package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.writing.api.CheckStylePreviewRequest;
import com.novelagent.writing.api.GenerateStylePreviewRequest;
import com.novelagent.writing.api.ReviseStylePreviewRequest;
import com.novelagent.writing.domain.QualityDimension;
import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.QualityScore;
import com.novelagent.writing.domain.ReviewIssue;
import com.novelagent.writing.domain.StylePreviewReview;
import com.novelagent.writing.domain.StylePreviewSource;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StylePreviewEditingServiceTest {
    private final UUID project = UUID.randomUUID();
    private final StylePreviewReviewStore store = mock(StylePreviewReviewStore.class);
    private final WritingGenerationWorkflow workflow = mock(WritingGenerationWorkflow.class);
    private final StylePreviewEditingService service = new StylePreviewEditingService(store, workflow);
    private final WritingStylePreviewContent text = new WritingStylePreviewContent("选座", "教室后门开着。她攥着座位名单。原有事件继续。");
    private final StylePreviewSource source = new StylePreviewSource(UUID.randomUUID(), 0,
            WritingStylePresets.all().get(1), "DEEPSEEK", 800, "保留事件", text);
    private final StylePreviewReviewStore.Snapshot snapshot = snapshot();
    private final QualityReviewContent quality = quality(text.body());
    private final StylePreviewReview report = StylePreviewReview.create(project, "a".repeat(64), source, quality);

    @Test
    void checksCandidateAndStoresOnlyReportWithoutApplyingStyle() {
        when(store.snapshot(eq(project), any())).thenReturn(snapshot);
        when(workflow.reviewStylePreview(eq(project), any(), any(), any(), any())).thenReturn(quality);
        when(store.save(project, snapshot, quality)).thenReturn(report);
        var result = service.check(project, request());
        assertThat(result.preview().profile()).isEqualTo(source.profile());
        assertThat(result.preview().content()).isEqualTo(text);
        assertThat(result.reviewMode()).isEqualTo("MODEL");
        verify(store).save(project, snapshot, quality);
    }

    @Test
    void rejectsFakeEvidenceBeforeSavingReport() {
        when(store.snapshot(eq(project), any())).thenReturn(snapshot);
        when(workflow.reviewStylePreview(eq(project), any(), any(), any(), any())).thenReturn(quality("不存在的原文"));
        assertThatThrownBy(() -> service.check(project, request())).isInstanceOf(IllegalArgumentException.class);
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void revisesOnlySelectedServerStoredIssuesAndFencesBeforeAndAfterModel() {
        when(store.get(project, report.getId())).thenReturn(report);
        when(store.snapshot(project, source)).thenReturn(snapshot);
        var revised = new WritingStylePreviewContent("选座", "原有事件继续。");
        when(workflow.reviseStylePreview(eq(project), any(), any(), any(), eq(source), eq(ModelProvider.DEEPSEEK), anyString()))
                .thenReturn(revised);
        var result = service.revise(project, report.getId(), revision(List.of("E1")));
        assertThat(result.content()).isEqualTo(revised);
        assertThat(report.getSource().content()).isEqualTo(text);
        verify(store).claim(project, report.getId(), snapshot.hash());
        verify(store).requireCurrent(project, snapshot);
        var feedback = ArgumentCaptor.forClass(String.class);
        verify(workflow).reviseStylePreview(eq(project), any(), any(), any(), eq(source), eq(ModelProvider.DEEPSEEK), feedback.capture());
        assertThat(feedback.getValue()).contains("教室后门开着", "不能编造道具来源", "保留原文", "先删除");
        assertThat(feedback.getValue()).doesNotContain("检查名单来源");
    }

    @Test
    void rejectsUnknownOrDuplicateIssuesWithoutClaimOrModelCall() {
        when(store.get(project, report.getId())).thenReturn(report);
        for (var ids : List.of(List.of("unknown"), List.of("E1", "E1"), List.<String>of())) {
            assertThatThrownBy(() -> service.revise(project, report.getId(), revision(ids))).isInstanceOf(IllegalArgumentException.class);
        }
        verify(store, never()).claim(any(), any(), any());
        verify(workflow, never()).reviseStylePreview(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleReportClaimStopsRevisionBeforeModelCall() {
        when(store.get(project, report.getId())).thenReturn(report);
        when(store.snapshot(project, source)).thenReturn(snapshot);
        when(store.claim(project, report.getId(), snapshot.hash())).thenThrow(new IllegalStateException("依据已变化"));
        assertThatThrownBy(() -> service.revise(project, report.getId(), revision(List.of("E1")))).hasMessage("依据已变化");
        verify(workflow, never()).reviseStylePreview(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void templateCannotPretendToPerformSemanticRevision() {
        assertThatThrownBy(() -> service.revise(project, report.getId(),
                new ReviseStylePreviewRequest(ModelProvider.LOCAL_TEMPLATE, List.of("E1"), null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能执行语义修订");
        verify(store, never()).get(any(), any());
    }

    @Test
    void reportAllowsOnlyOneAttemptAndSourceRemainsImmutable() {
        report.beginRevision();
        assertThat(report.isRevisionAttempted()).isTrue();
        assertThatThrownBy(report::beginRevision).isInstanceOf(IllegalStateException.class);
        assertThat(report.getSource().content()).isEqualTo(text);
    }

    private CheckStylePreviewRequest request() {
        return new CheckStylePreviewRequest(new GenerateStylePreviewRequest(source.outlineVersionId(), 0L,
                source.profile(), ModelProvider.DEEPSEEK, 800, "保留事件"), text);
    }

    private ReviseStylePreviewRequest revision(List<String> ids) {
        return new ReviseStylePreviewRequest(ModelProvider.DEEPSEEK, ids, "只改选中内容");
    }

    private StylePreviewReviewStore.Snapshot snapshot() {
        var bibleContent = new StoryBibleContent("校园选座", "误会", "高中", List.of(), "我", "成长", List.of(), List.of(),
                "靠近", "友情", "回顾", "和解", List.of(), List.of());
        var bible = StoryBibleVersion.create(UUID.randomUUID(), project, 1, "LOCAL_TEMPLATE", null, null, null, bibleContent);
        var chapter = new ChapterPlan(1, "选座", "我", "选座", "入座", "关系", "停顿", 1000, 2000);
        var arc = new OutlineArc(1, "校园", "选座", "误会", "入座", "开始", 1000, 2000, List.of(chapter));
        var budget = new OutlineWordBudget(3000, 2000, 5000, 1, 2, 1500, 1000, 2000);
        var outline = OutlineVersion.create(source.outlineVersionId(), project, 1, "LOCAL_TEMPLATE", null, bible.getId(), budget,
                new OutlineContent("校园", "选座", "回顾", "推进", 2000, 5000, List.of(arc)));
        return new StylePreviewReviewStore.Snapshot(new WritingContextService.Context(outline, bible, arc, chapter), source, "a".repeat(64));
    }

    private static QualityReviewContent quality(String evidence) {
        return new QualityReviewContent("细节需要复核", Arrays.stream(QualityDimension.values())
                .map(d -> new QualityScore(d, null, "仅验证检查流程")).toList(), List.of(
                new ReviewIssue("E1", "INFO", "SCENE", "开门细节未利用", evidence, "先删除无效细节，保留事件", false),
                new ReviewIssue("E2", "WARNING", "LOGIC", "检查名单来源", evidence, "不要编造来源", false)));
    }
}
