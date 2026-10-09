package com.novelagent.project.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.FreeTextPlanningRequest;
import com.novelagent.project.domain.NovelProject;
import java.util.UUID;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BookTitleServiceTest {
    private final UUID projectId = UUID.randomUUID(), importId = UUID.randomUUID();
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final FreeTextPlanningRequest text = mock(FreeTextPlanningRequest.class);
    private final NovelProject project = mock(NovelProject.class);
    private final BookTitleService titles = new BookTitleService(access, jdbc, text);
    @Test void suppliedTitleDoesNotConsumeAModelRequest() {
        when(access.requireOwnedProject(projectId)).thenReturn(project);
        titles.generateIfNeeded(projectId, importId, ModelProvider.LOCAL_CODEX);
        verifyNoInteractions(text, jdbc);
    }
    @Test void blankTitleUsesSourceAndCompareAndSetWithoutOverwritingAnAuthorRename() {
        setup();
        when(text.request(eq(projectId), eq("BOOK_TITLE"), eq(ModelProvider.LOCAL_CODEX), contains("原文"), eq("BOOK_TITLE"), eq(1000)))
                .thenReturn("全班都知道她喜欢我");
        titles.generateIfNeeded(projectId, importId, ModelProvider.LOCAL_CODEX);
        verify(jdbc).update(contains("AND name = ?"), eq("全班都知道她喜欢我"), eq(projectId), eq("待生成书名"));
    }
    @Test void commentaryIsNotSavedAsATitle() {
        setup();
        when(text.request(any(), anyString(), any(), anyString(), anyString(), anyInt())).thenReturn("推荐书名\n理由：……");
        assertThatThrownBy(() -> titles.generateIfNeeded(projectId, importId, ModelProvider.LOCAL_CODEX))
                .hasMessageContaining("书名响应无效");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }
    private void setup() {
        when(access.requireOwnedProject(projectId)).thenReturn(project);
        when(project.getSetting("automaticTitle")).thenReturn(Map.of("pending", true));
        when(project.getName()).thenReturn("待生成书名");
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(projectId), eq(importId))).thenReturn("原文");
    }
}
