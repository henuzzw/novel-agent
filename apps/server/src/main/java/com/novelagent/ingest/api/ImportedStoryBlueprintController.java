package com.novelagent.ingest.api;

import com.novelagent.ingest.application.ImportedStoryBlueprintService;
import com.novelagent.planning.domain.SnowflakePlan;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/imports/{importId}/actions")
public class ImportedStoryBlueprintController {
    private final ImportedStoryBlueprintService service;
    public ImportedStoryBlueprintController(ImportedStoryBlueprintService service) { this.service = service; }
    @PostMapping("/prepare-snowflake")
    public SnowflakePlan generate(@PathVariable UUID projectId, @PathVariable UUID importId,
            @Valid @RequestBody PrepareImportedStoryRequest input) {
        return service.generate(projectId, importId, input);
    }

    public record ResumeRequest(@jakarta.validation.constraints.NotNull UUID planId) { }

    @PostMapping("/resume-snowflake")
    public SnowflakePlan resume(@PathVariable UUID projectId, @PathVariable UUID importId,
            @Valid @RequestBody ResumeRequest input) {
        return service.resume(projectId, importId, input.planId());
    }
}
