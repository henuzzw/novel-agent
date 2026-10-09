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
    void usesNewThreadAndOnlyReplacementProtocolWithWritingBasis() throws Exception {
        var gateway = mock(StructuredModelGateway.class);
        when(gateway.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenReturn("{\"replacement\":\"new\"}");
        UUID project = UUID.randomUUID();
        var source = new ManuscriptLocalEditStore.Snapshot(project, 1, UUID.randomUUID(), 0, null,
                new ManuscriptContent("title", "old", "summary", List.of()), "chapter plan + style", "hash", null);
        var model = new ManuscriptLocalEditModel(gateway, new ObjectMapper());
        assertThat(model.replace(project, source, new ManuscriptLocalEditSelection("old", 0, 1), ModelProvider.LOCAL_CODEX, "clarify")).isEqualTo("new");
        var user = ArgumentCaptor.forClass(String.class);
        verify(gateway).request(eq(project), eq("MANUSCRIPT_LOCAL_EDIT"), eq(ModelProvider.LOCAL_CODEX), anyString(),
                user.capture(), any(), eq("manuscript_local_edit"), eq(12000), eq(CodexSessionPolicy.NEW_THREAD));
        var input = new ObjectMapper().readTree(user.getValue());
        assertThat(input.path("basis").asText()).isEqualTo(source.context());
        assertThat(input.path("selection").asText()).isEqualTo("old");
        assertThat(input.path("offsetUtf16").asInt()).isZero();
        assertThat(input.path("occurrence").asInt()).isEqualTo(1);
        when(gateway.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenReturn("{\"replacement\":\"new\",\"body\":\"overwrite\"}");
        assertThatThrownBy(() -> model.replace(project, source, new ManuscriptLocalEditSelection("old", 0, 1), ModelProvider.LOCAL_CODEX, "clarify"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
