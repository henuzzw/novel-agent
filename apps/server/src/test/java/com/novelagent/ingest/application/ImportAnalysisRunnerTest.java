package com.novelagent.ingest.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.ingest.domain.ImportAnalysis;
import com.novelagent.ingest.infrastructure.ImportAnalysisPrompt;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportAnalysisRunnerTest {
    private final ImportAnalysisStore store = mock(ImportAnalysisStore.class);
    private final StructuredModelGateway models = mock(StructuredModelGateway.class);
    private final ImportAnalysisRunner runner = new ImportAnalysisRunner(store, models, new ImportAnalysisPrompt(new ObjectMapper()));
    private final UUID project = UUID.randomUUID(), source = UUID.randomUUID(), id = UUID.randomUUID();
    private ImportAnalysisStore.Report report() { return new ImportAnalysisStore.Report(id, project, source, ModelProvider.DEEPSEEK, "hash", List.of(), 0, "RUNNING", new ImportAnalysis.Content(List.of(), List.of()), List.of(), null, null, 1, Instant.now()); }
    @Test void onlyRunsOneChunkWithANewSessionAndNoAutomaticConfirmation() {
        var claim = new ImportAnalysisStore.Claim(report(), "原文"); when(store.claim(project, source, id, 0)).thenReturn(claim);
        when(models.request(eq(project), eq("IMPORT_SOURCE_ANALYSIS"), eq(ModelProvider.DEEPSEEK), anyString(), eq("原文"), any(), eq("import_source_analysis"), eq(12000), eq(CodexSessionPolicy.NEW_THREAD), any())).thenAnswer(call -> {
            ((java.util.function.Consumer<String>) call.getArgument(9)).accept("output"); return "output";
        });
        var expected = new ImportAnalysisStore.View(report(), false); when(store.finish(claim, "output")).thenReturn(expected);
        assertThat(runner.next(project, source, id, 0)).isSameAs(expected); verify(store).finish(claim, "output");
        verify(models).request(eq(project), eq("IMPORT_SOURCE_ANALYSIS"), eq(ModelProvider.DEEPSEEK), anyString(), eq("原文"), any(), eq("import_source_analysis"), eq(12000), eq(CodexSessionPolicy.NEW_THREAD), any()); verifyNoMoreInteractions(models);
    }
    @Test void recordsFailureWithoutRetries() {
        var claim = new ImportAnalysisStore.Claim(report(), "原文"); when(store.claim(project, source, id, 0)).thenReturn(claim);
        var error = new IllegalArgumentException("timeout"); when(models.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any(), any())).thenThrow(error);
        runner.next(project, source, id, 0); verify(store).fail(claim, error); verify(models, times(1)).request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any(), any());
    }
    @Test void staleClaimStopsBeforeAnyPaidCall() {
        when(store.claim(project, source, id, 0)).thenThrow(new IllegalArgumentException("来源变化"));
        assertThatThrownBy(() -> runner.next(project, source, id, 0)).hasMessageContaining("来源变化"); verifyNoInteractions(models);
    }
    @Test void failedSaveIsRecordedWithinGatewayAndDoesNotAdvanceOrRetry() {
        var claim = new ImportAnalysisStore.Claim(report(), "原文"); when(store.claim(project, source, id, 0)).thenReturn(claim);
        var error = new IllegalArgumentException("原文解析输出校验失败：items[0].category");
        when(store.finish(claim, "raw")).thenThrow(error);
        when(models.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any(), any()))
                .thenAnswer(call -> { ((java.util.function.Consumer<String>) call.getArgument(9)).accept("raw"); return "raw"; });
        var failed = new ImportAnalysisStore.View(report(), false); when(store.get(project, source, id)).thenReturn(failed);
        assertThat(runner.next(project, source, id, 0)).isSameAs(failed);
        verify(store).fail(claim, error);
        verify(models, times(1)).request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any(), any());
    }
}
