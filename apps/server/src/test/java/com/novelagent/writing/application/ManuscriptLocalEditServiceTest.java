package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.ManuscriptLocalEditRequest;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.infrastructure.ManuscriptLocalEditModel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManuscriptLocalEditServiceTest {
    private final UUID project = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final ManuscriptLocalEditStore store = mock(ManuscriptLocalEditStore.class);
    private final ManuscriptLocalEditModel model = mock(ManuscriptLocalEditModel.class);
    private final ManuscriptLocalEditService service = new ManuscriptLocalEditService(store, model);

    private ManuscriptLocalEditRequest request(ModelProvider provider, boolean authorized) {
        return new ManuscriptLocalEditRequest(sourceId, 3L, "old", 1, 7, provider, "clarify", authorized);
    }

    private ManuscriptLocalEditStore.Snapshot snapshot() {
        var snapshot = new ManuscriptLocalEditStore.Snapshot(project, 1, sourceId, 3, UUID.randomUUID(),
                new ManuscriptContent("title", "before old after", "summary", List.of()), "contract/style", "hash", null);
        when(store.snapshot(project, 1, sourceId, 3)).thenReturn(snapshot);
        return snapshot;
    }

    @Test
    void requiresAuthorizationBeforeReadingOrCallingModel() {
        assertThatThrownBy(() -> service.edit(project, 1, request(ModelProvider.DEEPSEEK, false))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(store, model);
    }

    @Test
    void templateReturnsNotAssessedWithoutSavingOrCallingModel() {
        snapshot();
        var result = service.edit(project, 1, request(ModelProvider.LOCAL_TEMPLATE, true));
        assertThat(result.assessment()).isEqualTo("NOT_ASSESSED");
        assertThat(result.manuscript()).isNull();
        assertThat(result.replacement()).isNull();
        verifyNoInteractions(model);
        verify(store, org.mockito.Mockito.never()).save(any(), any(), any(), any());
    }

    @Test
    void validatesExactSpliceBeforeSavingAndPropagatesSaveConflict() {
        var snapshot = snapshot();
        when(model.replace(eq(project), eq(snapshot), any(), eq(ModelProvider.DEEPSEEK), eq("clarify"))).thenReturn("new");
        when(store.save(eq(snapshot), any(), eq("new"), any())).thenThrow(new ManuscriptLocalEditConflictException("stale style"));
        assertThatThrownBy(() -> service.edit(project, 1, request(ModelProvider.DEEPSEEK, true)))
                .isInstanceOf(ManuscriptLocalEditConflictException.class);
        verify(store).save(eq(snapshot), any(), eq("new"), any());
    }
}
