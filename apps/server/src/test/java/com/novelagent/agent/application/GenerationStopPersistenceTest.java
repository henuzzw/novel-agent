package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.ingest.application.ImportAnalysisStore;
import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.ingest.infrastructure.ImportAnalysisOutputParser;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.project.application.ProjectAccessService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class GenerationStopPersistenceTest {
    @Test void analysisCancellationKeepsVersionGuardAndDoesNotMasqueradeAsOrdinaryFailure() {
        var jdbc = mock(JdbcTemplate.class);
        var store = new ImportAnalysisStore(mock(ProjectAccessService.class), mock(WorkImportService.class), jdbc,
                new ObjectMapper(), mock(ImportAnalysisOutputParser.class));
        var report = mock(ImportAnalysisStore.Report.class);
        when(report.id()).thenReturn(UUID.randomUUID());
        when(report.version()).thenReturn(3L);
        var claim = new ImportAnalysisStore.Claim(report, "input");
        store.fail(claim, new GenerationStoppedException());
        store.fail(claim, new IllegalArgumentException("普通错误"));
        var values = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(argThat(sql -> sql.contains("status = 'RUNNING' AND row_version = ?")), values.capture());
        assertThat(values.getAllValues().get(0)[0]).isEqualTo("CANCELLED");
        assertThat(values.getAllValues().get(0)[3]).isEqualTo(3L);
        assertThat(values.getAllValues().get(1)[0]).isEqualTo("FAILED");
    }


}
