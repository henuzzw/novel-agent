package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.agent.api.AgentRunOutputResponse;
import com.novelagent.agent.infrastructure.AgentRunQueryRepository;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexAppServerException;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentRunOutputTest {
    @Test void bufferIsProjectScopedBoundedAndDiscardedAfterCompletion() {
        var outputs = new AgentRunOutputBuffer();
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        outputs.start(project, run);
        outputs.replace(run, "x".repeat(200_001));
        assertThat(outputs.get(project, run).truncated()).isTrue();
        assertThat(outputs.get(project, run).text()).hasSize(200_000);
        assertThat(outputs.get(UUID.randomUUID(), run)).isNull();
        outputs.remove(run);
        assertThat(outputs.get(project, run)).isNull();
        outputs.replace(run, "late output");
        assertThat(outputs.get(project, run)).isNull();
    }

    @Test void failureDetailsRetainUsefulMessageButRedactCredentialsAndPaths() {
        var error = ModelFailureDetails.from(new CodexAppServerException(
                "等待超时 Bearer private-secret api_key=secret-value sk-abc123 https://host/path?token=secret C:\\Users\\private\\auth.json",
                new TimeoutException()));
        assertThat(error.category()).isEqualTo("TIMEOUT");
        assertThat(error.summary()).contains("等待超时");
        assertThat(error.detail()).contains("等待超时", "REDACTED", "LOCAL_PATH")
                .doesNotContain("private-secret", "secret-value", "sk-abc123", "token=secret", "private\\auth.json");
        assertThat(ModelFailureDetails.from(new IllegalStateException("You hit your usage limit")).category()).isEqualTo("USAGE_LIMIT");
    }

    @Test void stalledGenerationExplainsIdleTimeoutRatherThanAStreamingDeadline() {
        var error = ModelFailureDetails.from(new CodexAppServerException(
                "等待 Codex 完成生成超时（连续无新进展等待上限 1200 秒）", new TimeoutException()));
        assertThat(error.category()).isEqualTo("TIMEOUT");
        assertThat(error.summary()).contains("没有新的生成进展", "空闲等待上限")
                .doesNotContain("降低推理强度");
        assertThat(error.detail()).contains("连续无新进展", "1200 秒");
    }

    @Test void recorderKeepsPartialFailedResponseWithoutPublishingOrLoggingIt() {
        var jdbc = mock(JdbcTemplate.class);
        var buffer = new AgentRunOutputBuffer();
        var recorder = new AgentRunRecorder(jdbc, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, buffer);
        UUID project = UUID.randomUUID();
        assertThatThrownBy(() -> recorder.record(project, "IMPORT_REVERSE_BIBLE", ModelProvider.LOCAL_CODEX,
                "system", "input", () -> {
                    recorder.progressSink().accept("{\"logline\":\"未完成");
                    throw new CodexAppServerException("等待 Codex 完成生成超时（等待上限 600 秒）", new TimeoutException());
                })).isInstanceOf(CodexAppServerException.class);
        var values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("UPDATE agent_run")), values.capture());
        Object[] stored = values.getValue();
        assertThat(stored[0]).isEqualTo("FAILED");
        assertThat(stored[8]).isNotEqualTo(0L);
        assertThat(stored[12]).asString().contains("等待超时");
        assertThat(stored[13]).isEqualTo("{\"logline\":\"未完成");
        assertThat(stored[16]).isEqualTo("TIMEOUT");
        assertThat(stored[17]).asString().contains("600 秒");
        UUID run = (UUID) stored[18];
        assertThat(buffer.get(project, run)).isNull();
        recorder.progressSink().accept("after completion");
        verify(jdbc, org.mockito.Mockito.times(2)).update(anyString(), any(Object[].class));
    }

    @Test void outputReadChecksOwnershipAndNeverPrefersLiveContentOverFinishedRecord() {
        var access = mock(ProjectAccessService.class);
        var repository = mock(AgentRunQueryRepository.class);
        var buffer = new AgentRunOutputBuffer();
        var service = new AgentRunQueryService(access, repository, buffer);
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        var saved = new AgentRunOutputResponse(run, "RUNNING", null, false, null, null, null, null);
        when(repository.output(project, run)).thenReturn(Optional.of(saved));
        buffer.start(project, run); buffer.replace(run, "streamed output");
        assertThat(service.output(project, run).orElseThrow().responseText()).isEqualTo("streamed output");
        var finished = new AgentRunOutputResponse(run, "SUCCEEDED", "final output", false, null, null, null, 5L);
        when(repository.output(project, run)).thenReturn(Optional.of(finished));
        assertThat(service.output(project, run)).contains(finished);
        var other = UUID.randomUUID();
        org.mockito.Mockito.doThrow(new ProjectNotFoundException(other)).when(access).requireOwnedProject(other);
        assertThatThrownBy(() -> service.output(other, run)).isInstanceOf(ProjectNotFoundException.class);
        verify(repository, org.mockito.Mockito.never()).output(other, run);
    }

    @Test void emptySubscriptionDoesNotStartModelOrReturnAnEmitter() {
        var queries = mock(AgentRunQueryService.class);
        var service = new AgentRunStreamService(queries);
        UUID project = UUID.randomUUID(), run = UUID.randomUUID();
        when(queries.output(project, run)).thenReturn(Optional.empty());
        assertThat(service.open(project, run)).isEmpty();
        org.mockito.Mockito.clearInvocations(queries);
        service.tick();
        verifyNoInteractions(queries);
    }
}
