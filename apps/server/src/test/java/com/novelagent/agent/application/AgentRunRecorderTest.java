package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.argThat;
import org.mockito.ArgumentCaptor;

import com.novelagent.planning.application.ModelProvider;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentRunRecorderTest {
    private JdbcTemplate jdbc;
    private AgentRunRecorder recorder;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        recorder = new AgentRunRecorder(jdbc, new BigDecimal("1"), new BigDecimal("2"),
                BigDecimal.ZERO, BigDecimal.ZERO);
    }

    @Test
    void recordsSuccessfulModelRun() {
        String output = recorder.record(UUID.randomUUID(), "OUTLINE", ModelProvider.DEEPSEEK,
                "系统指令", "生成一个三章大纲", () -> "生成完成");

        assertThat(output).isEqualTo("生成完成");
        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
        ArgumentCaptor<Object[]> values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("system_prompt, user_prompt")), values.capture());
        assertThat(values.getValue()[5]).isEqualTo("系统指令");
        assertThat(values.getValue()[6]).isEqualTo("生成一个三章大纲");
    }

    @Test
    void recordsFailureAndRethrows() {
        assertThatThrownBy(() -> recorder.record(UUID.randomUUID(), "MANUSCRIPT",
                ModelProvider.LOCAL_CODEX, "系统指令", "生成正文", () -> {
                    throw new IllegalStateException("模型不可用");
                })).isInstanceOf(IllegalStateException.class).hasMessage("模型不可用");

        verify(jdbc, times(2)).update(anyString(), any(Object[].class));
    }

    @Test
    void storesActualUsageSeparatelyFromEstimatesAndStoresOnlyRequestHashesInSnapshot() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var snapshot = AgentRunRecorder.RequestSnapshot.capture(
                new AgentRunRecorder.EffectiveSettings(ModelProvider.DEEPSEEK, "deepseek-flash", "none", 9L),
                "private system", "private manuscript", mapper.createObjectNode(), "schema", 200, "STATELESS");
        var usage = new AgentRunRecorder.Usage(123, 45, 168L, 30L, 12L);
        var result = new com.novelagent.planning.infrastructure.DeepSeekStructuredOutputClient.ResponseResult("{}", usage);

        assertThat(recorder.record(UUID.randomUUID(), "OUTLINE", ModelProvider.DEEPSEEK,
                "private system", "private manuscript", snapshot, () -> result)).isSameAs(result);

        ArgumentCaptor<Object[]> inserts = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("INSERT INTO")), inserts.capture());
        var storedSnapshot = mapper.readTree((String) inserts.getValue()[9]);
        assertThat(storedSnapshot.at("/effectiveSettings/version").asLong()).isEqualTo(9);
        assertThat(storedSnapshot.path("systemPromptHash").asText()).hasSize(64);
        assertThat(storedSnapshot.toString()).doesNotContain("private system", "private manuscript");

        ArgumentCaptor<Object[]> updates = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("UPDATE agent_run")), updates.capture());
        Object[] values = updates.getValue();
        assertThat(values[1]).isEqualTo(values[7]).isNotEqualTo(123L);
        assertThat(values[2]).isEqualTo(values[8]).isEqualTo(1L);
        assertThat(values[3]).isEqualTo("ACTUAL");
        assertThat(values[4]).isEqualTo(123L);
        assertThat(values[5]).isEqualTo(45L);
        assertThat(mapper.readTree((String) values[6]).path("cachedInputTokens").asLong()).isEqualTo(30);
        assertThat(values[7]).isNotEqualTo(123L);
        assertThat(values[8]).isEqualTo(1L);
    }

    @Test
    void missingUsageIsEstimatedAndDoesNotPopulateActualFields() {
        recorder.record(UUID.randomUUID(), "OUTLINE", ModelProvider.DEEPSEEK, "system", "input", () -> "{}");
        ArgumentCaptor<Object[]> updates = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("UPDATE agent_run")), updates.capture());
        Object[] values = updates.getValue();
        assertThat(values[3]).isEqualTo("ESTIMATED");
        assertThat(values[4]).isNull();
        assertThat(values[5]).isNull();
        assertThat(values[6]).isNull();
    }

    @Test
    void failedRunKeepsReceivedUsageAndDoesNotPersistRawProviderError() {
        class Failure extends IllegalStateException implements AgentRunRecorder.UsageCarrier {
            @Override public AgentRunRecorder.Usage usage() {
                return new AgentRunRecorder.Usage(10, 2, 12L, null, null);
            }
        }
        assertThatThrownBy(() -> recorder.record(UUID.randomUUID(), "OUTLINE", ModelProvider.DEEPSEEK,
                "system", "input", () -> { throw new Failure(); })).isInstanceOf(Failure.class);
        ArgumentCaptor<Object[]> updates = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("UPDATE agent_run")), updates.capture());
        assertThat(updates.getValue()[0]).isEqualTo("FAILED");
        assertThat(updates.getValue()[3]).isEqualTo("ACTUAL");
        assertThat(updates.getValue()[12]).isEqualTo("模型调用失败，请展开响应与错误详情查看原因。");
        assertThat(updates.getValue()[15]).isEqualTo("Failure");
    }

    @Test
    void outputValidationFailureStoresResponseUsageAndSpecificErrorInsteadOfSuccess() {
        var usage = new AgentRunRecorder.Usage(100, 45, 145L, null, null);
        var result = new com.novelagent.planning.infrastructure.DeepSeekStructuredOutputClient.ResponseResult("{invalid output}", usage);
        var error = new IllegalArgumentException("原文解析输出校验失败：items[2].category：分类无效");
        assertThatThrownBy(() -> recorder.record(UUID.randomUUID(), "IMPORT_SOURCE_ANALYSIS", ModelProvider.DEEPSEEK,
                "system", "input", null, () -> result, value -> { throw error; })).isSameAs(error);
        ArgumentCaptor<Object[]> updates = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("UPDATE agent_run")), updates.capture());
        Object[] values = updates.getValue();
        assertThat(values[0]).isEqualTo("FAILED");
        assertThat(values[3]).isEqualTo("ACTUAL");
        assertThat(values[4]).isEqualTo(100L);
        assertThat(values[5]).isEqualTo(45L);
        assertThat(values[13]).isEqualTo("{invalid output}");
        assertThat(values[16]).isEqualTo("OUTPUT_VALIDATION");
        assertThat(values[17]).isEqualTo(error.getMessage());
    }

    @Test void cancelledLateOutputKeepsUsageButNeverRunsOutputProcessorOrLeavesInterruptFlag() {
        var controls = new GenerationControlRegistry();
        recorder = new AgentRunRecorder(jdbc, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, new AgentRunOutputBuffer(), controls);
        var id = new java.util.concurrent.atomic.AtomicReference<UUID>();
        org.mockito.Mockito.doAnswer(call -> { id.set((UUID) ((Object[]) call.getRawArguments()[1])[0]); return 1; })
                .when(jdbc).update(argThat(sql -> sql.contains("INSERT INTO")), any(Object[].class));
        var processed = new java.util.concurrent.atomic.AtomicBoolean();
        var result = new com.novelagent.planning.infrastructure.DeepSeekStructuredOutputClient.ResponseResult(
                "late output", new AgentRunRecorder.Usage(100, 20, 120L, null, null));
        UUID project = UUID.randomUUID();
        assertThatThrownBy(() -> recorder.record(project, "OUTLINE", ModelProvider.DEEPSEEK,
                "system", "input", null, () -> { controls.stopRun(project, id.get()); return result; },
                ignored -> processed.set(true))).isInstanceOf(GenerationStoppedException.class);
        assertThat(processed.get()).isFalse();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        var values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(argThat(sql -> sql.contains("UPDATE agent_run")), values.capture());
        assertThat(values.getValue()[0]).isEqualTo("CANCELLED");
        assertThat(values.getValue()[4]).isEqualTo(100L);
        assertThat(values.getValue()[13]).isEqualTo("late output");
        assertThat(values.getValue()[16]).isEqualTo("CANCELLED");
        assertThatThrownBy(() -> controls.stopRun(project, id.get())).isInstanceOf(GenerationStopConflictException.class);
    }
}
