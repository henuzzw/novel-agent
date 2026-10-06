package com.novelagent.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.agent.application.AgentRunQueryService;
import com.novelagent.agent.infrastructure.AgentRunQueryRepository;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.sql.ResultSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class AgentRunControllerTest {

    @Test
    void returnsFullPromptForOwnedProjectOnly() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NovelProjectRepository projects = mock(NovelProjectRepository.class);
        when(projects.findById(projectId)).thenReturn(Optional.of(
                NovelProject.create(projectId, ownerId, "测试项目", EntryMode.IDEA)));
        when(jdbc.query(anyString(), AgentRunControllerTest.<AgentRunPromptResponse>rowMapper(),
                eq(projectId), eq(runId))).thenAnswer(call -> {
                    RowMapper<AgentRunPromptResponse> mapper = call.getArgument(1);
                    ResultSet row = mock(ResultSet.class);
                    when(row.getObject("id", UUID.class)).thenReturn(runId);
                    when(row.getString("system_prompt")).thenReturn("完整系统提示词");
                    when(row.getString("user_prompt")).thenReturn("完整用户提示词");
                    when(row.getString("prompt_preview")).thenReturn("摘要");
                    return List.of(mapper.mapRow(row, 0));
                });
        AgentRunController controller = new AgentRunController(new AgentRunQueryService(
                new ProjectAccessService(projects, new CurrentActorProvider(ownerId)), new AgentRunQueryRepository(jdbc)));

        var response = controller.prompt(projectId, runId);

        assertThat(response.getBody().systemPrompt()).isEqualTo("完整系统提示词");
        assertThat(response.getBody().userPrompt()).isEqualTo("完整用户提示词");
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
    }

    @Test
    void rejectsPromptRequestForAnotherOwnerBeforeDatabaseQuery() {
        UUID projectId = UUID.randomUUID();
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        NovelProjectRepository projects = mock(NovelProjectRepository.class);
        when(projects.findById(projectId)).thenReturn(Optional.of(
                NovelProject.create(projectId, UUID.randomUUID(), "其他项目", EntryMode.IDEA)));
        AgentRunController controller = new AgentRunController(new AgentRunQueryService(
                new ProjectAccessService(projects, new CurrentActorProvider(UUID.randomUUID())), new AgentRunQueryRepository(jdbc)));

        assertThatThrownBy(() -> controller.prompt(projectId, UUID.randomUUID()))
                .isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> controller.requestSnapshot(projectId, UUID.randomUUID()))
                .isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void privateSnapshotEndpointReturnsNoStoreAndDoesNotExposePrompts() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        var service = mock(AgentRunQueryService.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        when(service.requestSnapshot(projectId, runId)).thenReturn(Optional.of(
                new AgentRunResponse.RequestSnapshotResponse(runId,
                        mapper.readTree("{\"effectiveSettings\":{\"provider\":\"DEEPSEEK\",\"model\":\"deepseek-flash\",\"version\":4}}"),
                        null, "ESTIMATED", null, null, 10L, 2L)));
        var response = new AgentRunController(service).requestSnapshot(projectId, runId);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        assertThat(mapper.writeValueAsString(response.getBody())).doesNotContain("systemPrompt\"", "userPrompt\"", "apiKey");
        when(service.requestSnapshot(projectId, runId)).thenReturn(Optional.empty());
        assertThat(new AgentRunController(service).requestSnapshot(projectId, runId).getStatusCode().value()).isEqualTo(404);
    }

    private static <T> RowMapper<T> rowMapper() {
        return any();
    }
}
