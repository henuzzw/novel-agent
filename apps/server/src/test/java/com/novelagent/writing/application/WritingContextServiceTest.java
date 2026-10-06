package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.memory.application.NovelMemoryService;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WritingContextServiceTest {
    private final UUID projectId = UUID.randomUUID();
    private final NovelProject project = NovelProject.create(projectId, UUID.randomUUID(), "小说", EntryMode.IDEA);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final OutlineVersionRepository outlines = mock(OutlineVersionRepository.class);
    private final StoryBibleVersionRepository bibles = mock(StoryBibleVersionRepository.class);
    private final NovelMemoryService memory = mock(NovelMemoryService.class);
    private final OutlineVersion outline = mock(OutlineVersion.class);
    private final StoryBibleVersion bible = mock(StoryBibleVersion.class);
    private final WritingContextService service = new WritingContextService(access, outlines, bibles, memory);

    @BeforeEach
    void sources() {
        UUID outlineId = UUID.randomUUID();
        UUID bibleId = UUID.randomUUID();
        project.publishOutline(outlineId);
        project.publishStoryBible(bibleId);
        when(access.requireOwnedProject(projectId)).thenReturn(project);
        when(outlines.findByIdAndProjectId(outlineId, projectId)).thenReturn(Optional.of(outline));
        when(bibles.findByIdAndProjectId(bibleId, projectId)).thenReturn(Optional.of(bible));
        when(outline.getId()).thenReturn(outlineId);
        when(outline.getProjectId()).thenReturn(projectId);
        when(outline.getSourceBibleVersionId()).thenReturn(bibleId);
        when(outline.getStatus()).thenReturn(OutlineStatus.PUBLISHED);
        when(bible.getId()).thenReturn(bibleId);
        when(bible.getStatus()).thenReturn(StoryBibleStatus.PUBLISHED);
        when(outline.getContent()).thenReturn(new OutlineContent("小说", "前提", "结构", "节奏", 8000, 12000,
                List.of(arc(2, chapter(7, "插叙"), chapter(10, "下一章")), arc(1, chapter(1, "开头"), chapter(4, "前章")))));
    }

    @Test
    void resolvesAcrossVolumesAndGapsInReadingOrderEvenForFlashbacks() {
        var context = service.context(projectId, 7);
        assertThat(context.previous().chapter().number()).isEqualTo(4);
        assertThat(context.chapter().number()).isEqualTo(7);
        assertThat(context.next().chapter().number()).isEqualTo(10);
        assertThat(context.previous().arc().ordinal()).isEqualTo(1);
        assertThat(context.next().arc().ordinal()).isEqualTo(2);
        assertThat(context.futureContext()).contains("未来规划边界", "人物已知信息", outline.getId().toString(), "下一章");
        assertThat(service.context(projectId, 1).previous()).isNull();
        assertThat(service.context(projectId, 10).next()).isNull();
        assertThat(service.context(projectId, 10).futureContext()).contains("末章，无下一章");
    }

    @Test
    void rejectsUnpublishedAndMismatchedBibleButPreviewAcceptsDrafts() {
        when(bible.getStatus()).thenReturn(StoryBibleStatus.DRAFT);
        assertThatThrownBy(() -> service.context(projectId, 7)).hasMessageContaining("必须已发布");
        when(bible.getStatus()).thenReturn(StoryBibleStatus.PUBLISHED);
        project.publishStoryBible(UUID.randomUUID());
        assertThatThrownBy(() -> service.context(projectId, 7)).hasMessageContaining("项目当前圣经一致");
        when(outline.getStatus()).thenReturn(OutlineStatus.DRAFT);
        when(bible.getStatus()).thenReturn(StoryBibleStatus.DRAFT);
        assertThat(service.previewContext(projectId, outline.getId()).chapter().number()).isEqualTo(1);
        verifyNoInteractions(memory);
    }

    @Test
    void capturesPolicyAndKeepsFourArgumentConstructor() {
        CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING).applyTo(project);
        var context = service.context(projectId, 7);
        assertThat(context.creativeStrategy().strategy()).isEqualTo(CreativeStrategy.FANQIE_GRIPPING);
        assertThat(service.previewContext(projectId, outline.getId()).creativeStrategy()).isEqualTo(context.creativeStrategy());
        CreativeStrategyPolicy.of(CreativeStrategy.STANDARD).applyTo(project);
        assertThat(context.creativeStrategy().strategy()).isEqualTo(CreativeStrategy.FANQIE_GRIPPING);
        assertThat(new WritingContextService.Context(outline, bible, context.arc(), context.chapter())
                .creativeStrategy().strategy()).isEqualTo(CreativeStrategy.STANDARD);
    }

    @Test
    void planAndPreviousSourceChangesInvalidateBoundaryFingerprint() {
        var context = service.context(projectId, 7);
        String initial = context.boundaryFingerprint();
        var next = new WritingContextService.ChapterBoundary(context.next().arc(), chapter(10, "新下一章"));
        var changed = new WritingContextService.Context(outline, bible, context.arc(), context.chapter(),
                context.previous(), next, context.creativeStrategy());
        assertThat(changed.boundaryFingerprint()).isNotEqualTo(initial);
        var memoryA = new NovelMemoryContext(List.of(), List.of(), null, List.of(), List.of(), "前章正文版本A");
        var memoryB = new NovelMemoryContext(List.of(), List.of(), null, List.of(), List.of(), "前章正文版本B");
        assertThat(WritingContextService.boundaryFingerprint(context, memoryA))
                .isNotEqualTo(WritingContextService.boundaryFingerprint(context, memoryB));
    }

    @Test
    void rejectsMissingOrForeignSourcesBeforeRecall() {
        when(bibles.findByIdAndProjectId(outline.getSourceBibleVersionId(), projectId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.context(projectId, 7)).hasMessageContaining("圣经不可用");
        verifyNoInteractions(memory);
    }

    private ChapterPlan chapter(int number, String title) {
        return new ChapterPlan(number, title, "主角", "目标", "事件", "揭示", "钩子", 2000, 3000);
    }

    private OutlineArc arc(int ordinal, ChapterPlan... chapters) {
        return new OutlineArc(ordinal, "卷" + ordinal, "目标", "冲突", "转折", "结果", 4000, 6000, List.of(chapters));
    }
}
