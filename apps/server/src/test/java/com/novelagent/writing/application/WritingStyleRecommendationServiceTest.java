package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.planning.api.StoryBibleResponse;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.StoryBibleService;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.api.RecommendWritingStyleRequest;
import com.novelagent.writing.domain.WritingStyleRecommendationContent;
import com.novelagent.writing.domain.WritingStyleRecommendationContent.Evidence;
import com.novelagent.writing.domain.WritingStyleRecommendationContent.Recommendation;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WritingStyleRecommendationServiceTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID bibleId = UUID.randomUUID();
    private final StoryBibleService bibles = mock(StoryBibleService.class);
    private final WritingGenerationWorkflow workflow = mock(WritingGenerationWorkflow.class);
    private final WritingStyleService styles = mock(WritingStyleService.class);
    private final WritingStyleRecommendationService service = new WritingStyleRecommendationService(bibles, workflow, styles);
    private final RecommendWritingStyleRequest request = new RecommendWritingStyleRequest(bibleId, 0L,
            ModelProvider.DEEPSEEK, "  少一些议论  ");

    @Test
    void resolvesRecommendedPresetToCanonicalProfileWithoutApplyingIt() {
        when(styles.presets(projectId)).thenReturn(WritingStylePresets.all());
        when(bibles.version(projectId, bibleId)).thenReturn(source(0, "冷静观察"));
        var content = new WritingStyleRecommendationContent("适合细节叙事", List.of(new Recommendation(
                "现实细腻", "关系通过生活细节展开", "保留悬念", List.of(new Evidence("theme", "记忆")))));
        when(workflow.recommendStyle(eq(projectId), any(), eq(ModelProvider.DEEPSEEK), eq("少一些议论")))
                .thenReturn(content);
        var result = service.recommend(projectId, request);
        assertThat(result.sourceBibleVersionId()).isEqualTo(bibleId);
        assertThat(result.sourceBibleRowVersion()).isZero();
        assertThat(result.recommendationMode()).isEqualTo("MODEL");
        assertThat(result.recommendations().getFirst().profile()).isEqualTo(WritingStylePresets.all().getFirst());
        assertThat(result.recommendations().getFirst().evidence()).containsExactly(new Evidence("theme", "记忆"));
    }

    @Test
    void rejectsStaleBibleBeforeModelCall() {
        when(bibles.version(projectId, bibleId)).thenReturn(source(1, "冷静观察"));
        assertThatThrownBy(() -> service.recommend(projectId, request)).isInstanceOf(ResourceVersionConflictException.class);
        verifyNoInteractions(workflow);
    }

    @Test
    void rejectsBibleChangedDuringModelCall() {
        when(bibles.version(projectId, bibleId)).thenReturn(source(0, "冷静观察"), source(1, "冷静观察"));
        when(workflow.recommendStyle(any(), any(), any(), any()))
                .thenReturn(new WritingStyleRecommendationContent("演示", List.of()));
        assertThatThrownBy(() -> service.recommend(projectId, request)).isInstanceOf(ResourceVersionConflictException.class);
    }

    @Test
    void rejectsRenderedContentChangedEvenWhenRowVersionIsUnchanged() {
        when(bibles.version(projectId, bibleId)).thenReturn(source(0, "冷静观察"), source(0, "人物名称已更新"));
        when(workflow.recommendStyle(any(), any(), any(), any()))
                .thenReturn(new WritingStyleRecommendationContent("演示", List.of()));
        assertThatThrownBy(() -> service.recommend(projectId, request))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("渲染内容已变化");
    }

    @Test
    void checksOwnedBibleBeforeGeneration() {
        when(bibles.version(projectId, bibleId)).thenThrow(new ProjectNotFoundException(projectId));
        assertThatThrownBy(() -> service.recommend(projectId, request)).isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(workflow);
    }

    private StoryBibleResponse source(long version, String style) {
        var content = new StoryBibleContent("旧信的故事", "记忆", "城市", List.of(), "主角", "成长", List.of(),
                List.of(), "误会", "代价", style, "和解", List.of(), List.of());
        return new StoryBibleResponse(bibleId, projectId, 2, "story-bible/1", StoryBibleStatus.DRAFT, "AUTHOR_EDIT",
                null, null, null, null, null, content, List.of(), version, Instant.EPOCH, Instant.EPOCH);
    }
}
