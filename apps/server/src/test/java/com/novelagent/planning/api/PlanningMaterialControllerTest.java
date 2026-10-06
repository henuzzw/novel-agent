package com.novelagent.planning.api;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.novelagent.planning.application.PlanningMaterialSyncService;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.project.application.ProjectNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PlanningMaterialControllerTest {
    @Test void readsDoNotTriggerSynchronizationAndUsePrivateResponses() throws Exception {
        var service = mock(PlanningMaterialSyncService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PlanningMaterialController(service)).build();
        UUID project = UUID.randomUUID();
        when(service.characters(project)).thenReturn(List.of());
        mvc.perform(get("/api/v1/projects/" + project + "/planning-materials/characters"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        verify(service).characters(project);
        verify(service, never()).syncCurrent(any());
        mvc.perform(post("/api/v1/projects/" + project + "/planning-materials/actions/sync"))
                .andExpect(status().isNoContent());
        verify(service).syncCurrent(project);
    }
    @Test void inaccessibleProjectsDoNotExposePlanningMaterials() throws Exception {
        var service = mock(PlanningMaterialSyncService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PlanningMaterialController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        UUID project = UUID.randomUUID();
        when(service.characters(project)).thenThrow(new ProjectNotFoundException(project));
        mvc.perform(get("/api/v1/projects/" + project + "/planning-materials/characters")).andExpect(status().isNotFound());
    }
}
