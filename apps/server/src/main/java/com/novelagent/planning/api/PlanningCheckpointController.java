package com.novelagent.planning.api;

import com.novelagent.planning.application.PlanningCheckpointRunner;
import com.novelagent.planning.application.PlanningCheckpointService;
import com.novelagent.planning.domain.PlanningCheckpoint;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/planning-checkpoints")
public class PlanningCheckpointController {
    private final PlanningCheckpointService checkpoints;
    private final PlanningCheckpointRunner runner;
    public PlanningCheckpointController(PlanningCheckpointService checkpoints, PlanningCheckpointRunner runner) {
        this.checkpoints = checkpoints; this.runner = runner;
    }
    public record VersionRequest(@NotNull @PositiveOrZero Long version) { }
    @GetMapping public List<PlanningCheckpoint> list(@PathVariable UUID projectId) { return checkpoints.list(projectId); }
    @GetMapping("/{id}") public PlanningCheckpoint get(@PathVariable UUID projectId, @PathVariable UUID id) { return checkpoints.get(projectId, id); }
    @PostMapping public PlanningCheckpoint create(@PathVariable UUID projectId,
            @RequestBody PlanningCheckpointService.CreateCommand request) { return checkpoints.create(projectId, request); }
    @PostMapping("/{id}/actions/run") public PlanningCheckpoint run(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest request) { return runner.run(projectId, id, request.version()); }
    @PostMapping("/{id}/actions/cancel") public PlanningCheckpoint cancel(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest request) { return checkpoints.cancel(projectId, id, request.version()); }
    @PostMapping("/{id}/actions/retry") public PlanningCheckpoint retry(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest request) { return checkpoints.retry(projectId, id, request.version()); }
    @PostMapping("/{id}/actions/reuse") public PlanningCheckpointService.ReusedResult reuse(@PathVariable UUID projectId, @PathVariable UUID id) {
        return checkpoints.reuse(projectId, id);
    }
}
