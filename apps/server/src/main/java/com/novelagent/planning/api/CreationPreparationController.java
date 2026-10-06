package com.novelagent.planning.api;

import com.novelagent.planning.application.CreationPreparationApprovalService;
import com.novelagent.planning.application.CreationPreparationContextService;
import com.novelagent.planning.application.CreationPreparationRunner;
import com.novelagent.planning.application.CreationPreparationStore;
import com.novelagent.planning.domain.CreationPreparation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/creation-preparations")
public class CreationPreparationController {
    private final CreationPreparationStore store;
    private final CreationPreparationRunner runner;
    private final CreationPreparationApprovalService approval;
    private final CreationPreparationContextService context;
    public CreationPreparationController(CreationPreparationStore store, CreationPreparationRunner runner,
            CreationPreparationApprovalService approval, CreationPreparationContextService context) {
        this.store = store; this.runner = runner; this.approval = approval; this.context = context;
    }
    public record Version(@NotNull @PositiveOrZero Long version) { }
    public record Edit(@NotNull @PositiveOrZero Long version, @NotNull CreationPreparation.World world, @NotNull CreationPreparation.Plot plot) { }
    public record ConfirmLink(@NotNull UUID requestId, @NotNull @PositiveOrZero Long planVersion, boolean authorConfirmed) { }
    @GetMapping
    public ResponseEntity<List<CreationPreparationStore.View>> list(@PathVariable UUID projectId) { return noStore(store.list(projectId)); }
    @GetMapping("/{id}")
    public ResponseEntity<CreationPreparationStore.View> get(@PathVariable UUID projectId, @PathVariable UUID id) { return noStore(store.get(projectId, id)); }
    @PostMapping
    public ResponseEntity<CreationPreparationStore.View> create(@PathVariable UUID projectId, @RequestBody CreationPreparationStore.Create input) {
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(store.create(projectId, input));
    }
    @PostMapping("/{id}/actions/run-next")
    public ResponseEntity<CreationPreparationStore.View> next(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Version input) { return noStore(runner.next(projectId, id, input.version())); }
    @PostMapping("/{id}/actions/run-all")
    public ResponseEntity<CreationPreparationStore.View> all(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Version input) { return noStore(runner.all(projectId, id, input.version())); }
    @PostMapping("/{id}/actions/cancel")
    public ResponseEntity<CreationPreparationStore.View> cancel(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Version input) { return noStore(store.action(projectId, id, input.version(), "cancel")); }
    @PostMapping("/{id}/actions/resume")
    public ResponseEntity<CreationPreparationStore.View> resume(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Version input) { return noStore(store.action(projectId, id, input.version(), "resume")); }
    @PutMapping("/{id}")
    public ResponseEntity<CreationPreparationStore.View> edit(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody Edit input) { return noStore(store.edit(projectId, id, input.version(), input.world(), input.plot())); }
    @PostMapping("/{id}/actions/confirm")
    public ResponseEntity<CreationPreparationStore.View> confirm(@PathVariable UUID projectId, @PathVariable UUID id, @RequestBody CreationPreparationApprovalService.Confirm input) { return noStore(approval.confirm(projectId, id, input)); }
    @GetMapping("/plan-links")
    public ResponseEntity<List<CreationPreparationContextService.Link>> links(@PathVariable UUID projectId) { return noStore(context.links(projectId)); }
    @GetMapping("/checkpoints")
    public ResponseEntity<List<CreationPreparationContextService.Checkpoint>> checkpoints(@PathVariable UUID projectId) { return noStore(context.checkpoints(projectId)); }
    @PostMapping("/plan-links/{id}/actions/confirm")
    public ResponseEntity<Void> confirmLink(@PathVariable UUID projectId, @PathVariable UUID id, @Valid @RequestBody ConfirmLink input) {
        context.confirmLink(projectId, id, input.requestId(), input.planVersion(), input.authorConfirmed()); return ResponseEntity.noContent().build();
    }
    private static <T> ResponseEntity<T> noStore(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
