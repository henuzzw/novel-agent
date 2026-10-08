package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.writing.application.ManuscriptLocalEditStore;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptLocalEditSelection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ManuscriptLocalEditModelTest {
    @Test
    void usesNewThreadAndOnlyReplacementSchemaWithContractAndStyleBoundaries() {
        var gateway = mock(StructuredModelGateway.class);
        when(gateway.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenReturn("{\"replacement\":\"new\"}");
        UUID project = UUID.randomUUID();
        var source = new ManuscriptLocalEditStore.Snapshot(project, 1, UUID.randomUUID(), 0, UUID.randomUUID(),
                new ManuscriptContent("title", "old", "summary", List.of()), "contract + style", "hash", null);
        var model = new ManuscriptLocalEditModel(gateway, new ObjectMapper());
        assertThat(model.replace(project, source, new ManuscriptLocalEditSelection("old", 0, 1), ModelProvider.LOCAL_CODEX, "clarify")).isEqualTo("new");
        var system = ArgumentCaptor.forClass(String.class);
        var user = ArgumentCaptor.forClass(String.class);
        verify(gateway).request(eq(project), eq("MANUSCRIPT_LOCAL_EDIT"), eq(ModelProvider.LOCAL_CODEX), system.capture(),
                user.capture(), any(), eq("manuscript_local_edit"), eq(12000), eq(CodexSessionPolicy.NEW_THREAD));
        assertThat(system.getValue()).contains("本章已确认事实", "当前风格", "不自动确认或提交正史", "不得新增故事事实");
        assertThat(user.getValue()).contains("contract + style", "offsetUtf16", "occurrence");
        when(gateway.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenReturn("{\"replacement\":\"new\",\"body\":\"overwrite\"}");
        assertThatThrownBy(() -> model.replace(project, source, new ManuscriptLocalEditSelection("old", 0, 1), ModelProvider.LOCAL_CODEX, "clarify"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
