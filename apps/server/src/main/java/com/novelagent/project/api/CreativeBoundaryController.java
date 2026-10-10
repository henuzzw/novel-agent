package com.novelagent.project.api;

import com.novelagent.project.application.CreativeBoundaryService;
import com.novelagent.project.domain.CreativeBoundaries;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Author choices override source inference; updates are versioned and never trigger paid generation. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/settings/creative-boundaries")
public class CreativeBoundaryController {
    private final CreativeBoundaryService service;
    public CreativeBoundaryController(CreativeBoundaryService service) { this.service = service; }
    public record Update(@NotNull CreativeBoundaries.WorldMode worldMode,
            @NotNull CreativeBoundaries.AdaptationScope adaptationScope,
            @NotNull @PositiveOrZero Long version, boolean resetAssessment) { }
    @GetMapping
    public CreativeBoundaryService.State get(@PathVariable UUID projectId) { return service.get(projectId); }
    @PutMapping
    public CreativeBoundaryService.State update(@PathVariable UUID projectId, @Valid @RequestBody Update input) {
        return service.update(projectId, input.worldMode(), input.adaptationScope(), input.version(), input.resetAssessment());
    }
}
