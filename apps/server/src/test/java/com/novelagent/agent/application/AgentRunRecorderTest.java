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
}
