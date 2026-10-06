package com.novelagent.planning.api;

import com.novelagent.planning.application.PlanningBatchRunner;
import com.novelagent.planning.application.PlanningBatchService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/planning-batches")
public class PlanningBatchController {
    private final PlanningBatchService batches;
    private final PlanningBatchRunner runner;

    public PlanningBatchController(PlanningBatchService batches, PlanningBatchRunner runner) {
        this.batches = batches;
        this.runner = runner;
    }

    public record VersionRequest(@NotNull @PositiveOrZero Long version) { }

    @GetMapping
    public List<PlanningBatchService.View> list(@PathVariable UUID projectId) {
        return batches.list(projectId);
    }

    @GetMapping("/{id}")
    public PlanningBatchService.View get(@PathVariable UUID projectId, @PathVariable UUID id) {
        return batches.get(projectId, id);
    }

    @PostMapping
    public ResponseEntity<PlanningBatchService.View> create(@PathVariable UUID projectId,
            @RequestBody PlanningBatchService.CreateCommand command) {
        return ResponseEntity.status(201).body(batches.create(projectId, command));
    }

    @PostMapping("/{id}/actions/run-next")
    public PlanningBatchService.View runNext(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest command) {
        return runner.runNext(projectId, id, command.version());
    }

    @PostMapping("/{id}/actions/cancel")
    public PlanningBatchService.View cancel(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest command) {
        return batches.cancel(projectId, id, command.version());
    }

    @PostMapping("/{id}/actions/resume")
    public PlanningBatchService.View resume(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest command) {
        return batches.resume(projectId, id, command.version());
    }

    @PostMapping("/{id}/actions/assemble")
    public ResponseEntity<OutlineResponse> assemble(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody VersionRequest command) {
        return ResponseEntity.status(201).body(batches.assemble(projectId, id, command.version()));
    }
}
