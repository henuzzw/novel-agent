package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.api.GenerateStylePreviewRequest;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class WritingStylePreviewServiceTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID outlineId = UUID.randomUUID();
    private final WritingContextService contexts = mock(WritingContextService.class);
    private final WritingGenerationWorkflow workflow = mock(WritingGenerationWorkflow.class);
    private final WritingStyleService styles = mock(WritingStyleService.class);
    private final WritingStylePreviewService service = new WritingStylePreviewService(contexts, workflow, styles);
    private final OutlineVersion outline = mock(OutlineVersion.class);
    private final StoryBibleVersion bible = mock(StoryBibleVersion.class);
    private final WritingContextService.Context context = new WritingContextService.Context(
            outline, bible, mock(OutlineArc.class), mock(ChapterPlan.class));

    @BeforeEach
    void setUp() {
        when(styles.resolveProfile(any())).thenAnswer(call -> call.getArgument(0));
        when(contexts.previewContext(projectId, outlineId)).thenReturn(context);
        when(outline.getId()).thenReturn(outlineId);
        when(outline.getGenerationNumber()).thenReturn(2);
        when(bible.getId()).thenReturn(UUID.randomUUID());
    }

    private GenerateStylePreviewRequest request(long version) {
        return new GenerateStylePreviewRequest(outlineId, version, WritingStylePresets.all().getFirst(),
                ModelProvider.LOCAL_TEMPLATE, null, "  更自然  ");
    }

    @Test
    void returnsIndependentPreviewAndUsesUnappliedProfileAndDefaultLength() {
        var input = request(0);
        var content = new WritingStylePreviewContent("开头", "她推开门，里面的人停下了交谈。");
        when(workflow.generateStylePreview(eq(projectId), any(), any(), any(), eq(input.profile()),
                eq(ModelProvider.LOCAL_TEMPLATE), eq(800), eq("更自然"))).thenReturn(content);
        var response = service.generate(projectId, input);
        assertThat(response.sourceOutlineVersionId()).isEqualTo(outlineId);
        assertThat(response.profile()).isEqualTo(input.profile());
        assertThat(response.targetWords()).isEqualTo(800);
        assertThat(response.previewMode()).isEqualTo("TEMPLATE");
        assertThat(response.content()).isEqualTo(content);
    }

    @Test
    void rejectsStaleOutlineBeforeCallingModel() {
        when(outline.getRowVersion()).thenReturn(1L);
        assertThatThrownBy(() -> service.generate(projectId, request(0)))
                .isInstanceOf(ResourceVersionConflictException.class);
        verifyNoInteractions(workflow);
    }

    @Test
    void rejectsOutlineChangedDuringGeneration() {
        when(outline.getRowVersion()).thenReturn(0L, 1L);
        when(workflow.generateStylePreview(any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(new WritingStylePreviewContent("开头", "正文"));
        assertThatThrownBy(() -> service.generate(projectId, request(0)))
                .isInstanceOf(ResourceVersionConflictException.class);
    }

    @Test
    void deniesAccessBeforeGeneration() {
        when(contexts.previewContext(projectId, outlineId)).thenThrow(new ProjectNotFoundException(projectId));
        assertThatThrownBy(() -> service.generate(projectId, request(0))).isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(workflow);
    }
}
