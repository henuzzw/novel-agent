package com.novelagent.ingest.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.ingest.domain.ImportPlanningMode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ImportedPlanningSourcesTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID projectId = UUID.randomUUID(), importId = UUID.randomUUID(), analysisId = UUID.randomUUID();
    private final ImportAnalysisStore analyses = mock(ImportAnalysisStore.class);
    private final WorkImportService imports = mock(WorkImportService.class);
    private final ImportedPlanningSources sources = new ImportedPlanningSources(analyses, imports);

    private com.fasterxml.jackson.databind.node.ObjectNode frozen() {
        var input = mapper.createObjectNode().put("mode", "ADAPT_SOURCE")
                .put("sourceAnalysisId", analysisId.toString()).put("sourceAnalysisVersion", 2)
                .put("sourceText", "完整原文\n保留格式").put("sourceChapterCount", 2).put("sourceTruncated", false);
        input.putObject("confirmedAnalysis").put("version", 2).put("summary", "已确认");
        return input;
    }

    @Test void loadsExactConfirmedSourceAndToleratesOnlyNumericNodeRepresentationChanges() {
        var input = frozen();
        var report = mapper.createObjectNode().put("version", 2L).put("summary", "已确认");
        when(analyses.requireConfirmed(projectId, importId, analysisId, 2L, ImportPlanningMode.ADAPT_SOURCE))
                .thenReturn(report);
        when(imports.planningSource(projectId, importId)).thenReturn(
                new WorkImportService.PlanningSource("完整原文\n保留格式", 2, 10, false));

        sources.requireUnchanged(projectId, importId, input, "来源已改变");

        verify(analyses).requireConfirmed(projectId, importId, analysisId, 2L, ImportPlanningMode.ADAPT_SOURCE);
        verify(imports).planningSource(projectId, importId);
        assertThat(input.path("confirmedAnalysis").path("version").isInt()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"text", "chapters", "truncated", "report", "numericType"})
    void sourceOrAnalysisChangeCannotPassFrozenSnapshotCheck(String change) {
        var input = frozen();
        var report = input.path("confirmedAnalysis").deepCopy();
        String text = "完整原文\n保留格式";
        int chapters = 2;
        boolean truncated = false;
        switch (change) {
            case "text" -> text = "完整原文 保留格式";
            case "chapters" -> chapters = 1;
            case "truncated" -> truncated = true;
            case "report" -> ((com.fasterxml.jackson.databind.node.ObjectNode) report).put("summary", "新结论");
            case "numericType" -> ((com.fasterxml.jackson.databind.node.ObjectNode) report).put("version", "2");
        }
        when(analyses.requireConfirmed(projectId, importId, analysisId, 2L, ImportPlanningMode.ADAPT_SOURCE))
                .thenReturn(report);
        when(imports.planningSource(projectId, importId)).thenReturn(
                new WorkImportService.PlanningSource(text, chapters, 10, truncated));
        assertThatThrownBy(() -> sources.requireUnchanged(projectId, importId, input, "来源已改变"))
                .hasMessage("来源已改变");
    }

    @Test void unconfirmedReportCannotReadOrPromoteImportText() {
        when(analyses.requireConfirmed(projectId, importId, analysisId, 2L, ImportPlanningMode.ADAPT_SOURCE))
                .thenThrow(new IllegalArgumentException("尚未确认"));
        assertThatThrownBy(() -> sources.requireUnchanged(projectId, importId, frozen(), "来源已改变"))
                .hasMessage("尚未确认");
        verifyNoInteractions(imports);
    }
}
