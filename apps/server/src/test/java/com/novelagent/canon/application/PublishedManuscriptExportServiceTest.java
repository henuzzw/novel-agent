package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.infrastructure.PublishedManuscriptExportRepository;
import com.novelagent.planning.domain.*;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.writing.domain.ManuscriptContent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PublishedManuscriptExportServiceTest {
    private final UUID projectId = UUID.randomUUID(), outlineId = UUID.randomUUID();
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final PublishedManuscriptExportRepository chapters = mock(PublishedManuscriptExportRepository.class);
    private final OutlineVersionRepository outlines = mock(OutlineVersionRepository.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final PublishedManuscriptExportService service = new PublishedManuscriptExportService(access, chapters, outlines, names, mapper);

    private void owned() {
        when(access.requireOwnedProject(projectId)).thenReturn(NovelProject.create(projectId, UUID.randomUUID(), "校园故事", EntryMode.MATERIALS));
        when(names.render(eq(projectId), anyString())).thenAnswer(call -> call.getArgument(1));
    }
    private PublishedManuscriptExportRepository.Chapter chapter(int number, UUID source) throws Exception {
        return new PublishedManuscriptExportRepository.Chapter(number, mapper.writeValueAsString(
                new ManuscriptContent("章节" + number, "原文" + number + "\n\n保留段落。", "不导出摘要", List.of("不导出备注"))), source);
    }

    @Test void exportsOnlySuppliedPublishedSourcesInOrderWithBookVolumeAndChapterTitles() throws Exception {
        owned();
        when(chapters.completedChapters(projectId)).thenReturn(List.of(chapter(1, outlineId), chapter(3, outlineId), chapter(4, outlineId)));
        var first = new OutlineArc(1, "第一卷 相遇", "目标", "冲突", "转折", "结果", 1000, 3000,
                List.of(new ChapterPlan(1,"一","计划",ChapterPlanStatus.PLANNED), new ChapterPlan(3,"三","计划",ChapterPlanStatus.PLANNED)));
        var second = new OutlineArc(2, "第二卷 分别", "目标", "冲突", "转折", "结果", 1000, 3000,
                List.of(new ChapterPlan(4,"四","计划",ChapterPlanStatus.PLANNED)));
        var version = mock(OutlineVersion.class);
        when(version.getContent()).thenReturn(new OutlineContent("旧大纲", "前提", "结构", "节奏", 1000, 6000, List.of(first, second)));
        when(outlines.findByIdAndProjectId(outlineId, projectId)).thenReturn(Optional.of(version));
        String output = service.export(projectId);
        assertThat(output).startsWith("# 校园故事\n\n## 第一卷 相遇\n\n### 第 1 章 · 章节1\n\n原文1\n\n保留段落。");
        assertThat(output).contains("### 第 3 章 · 章节3", "## 第二卷 分别\n\n### 第 4 章 · 章节4")
                .doesNotContain("第 2 章", "不导出摘要", "不导出备注", "旧大纲");
        assertThat(output.indexOf("第 3 章")).isLessThan(output.indexOf("第 4 章"));
        assertThat(output.split("## 第一卷 相遇", -1)).hasSize(2);
        verify(outlines, times(1)).findByIdAndProjectId(outlineId, projectId);
        verify(names, times(1)).render(eq(projectId), anyString());
    }

    @Test void rejectsEmptyExportInsteadOfDownloadingABlankBook() {
        owned(); when(chapters.completedChapters(projectId)).thenReturn(List.of());
        assertThatThrownBy(() -> service.export(projectId)).hasMessageContaining("暂无已发布且记忆整理完成");
        verifyNoInteractions(outlines); verify(names, never()).render(any(UUID.class), anyString());
    }

    @Test void rejectsUnauthorizedProjectBeforeReadingManuscripts() {
        when(access.requireOwnedProject(projectId)).thenThrow(new IllegalStateException("项目不可见"));
        assertThatThrownBy(() -> service.export(projectId)).hasMessage("项目不可见");
        verifyNoInteractions(chapters, outlines, names);
    }

    @Test void doesNotSilentlyUseNewerOutlineWhenOriginalSourceIsMissing() throws Exception {
        owned(); when(chapters.completedChapters(projectId)).thenReturn(List.of(chapter(1, outlineId)));
        when(outlines.findByIdAndProjectId(outlineId, projectId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.export(projectId)).hasMessageContaining("来源大纲不存在");
    }

    @Test void preservesBodyForChapterWithoutVolumeSource() throws Exception {
        owned(); when(chapters.completedChapters(projectId)).thenReturn(List.of(chapter(2, null)));
        assertThat(service.export(projectId)).contains("## 未分卷", "### 第 2 章 · 章节2", "原文2\n\n保留段落。");
    }
}
