package com.novelagent.ingest.api;

import com.novelagent.ingest.application.ImportAnalysisRunner;
import com.novelagent.ingest.application.ImportAnalysisStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/imports/{importId}/analyses")
public class ImportAnalysisController {
    private final ImportAnalysisStore store;
    private final ImportAnalysisRunner runner;
    public ImportAnalysisController(ImportAnalysisStore store, ImportAnalysisRunner runner) { this.store = store; this.runner = runner; }
    public record Version(@NotNull @PositiveOrZero Long version) { }
    @GetMapping
    public ResponseEntity<List<ImportAnalysisStore.View>> list(@PathVariable UUID projectId, @PathVariable UUID importId) { return response(store.list(projectId, importId)); }
    @GetMapping("/{id}")
    public ResponseEntity<ImportAnalysisStore.View> get(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id) { return response(store.get(projectId, importId, id)); }
    @PostMapping
    public ResponseEntity<ImportAnalysisStore.View> create(@PathVariable UUID projectId, @PathVariable UUID importId, @RequestBody ImportAnalysisStore.Create input) { return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(store.create(projectId, importId, input)); }
    @PostMapping("/{id}/actions/run-next")
    public ResponseEntity<ImportAnalysisStore.View> next(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id, @Valid @RequestBody Version input) { return response(runner.next(projectId, importId, id, input.version())); }
    @PostMapping("/{id}/actions/resume")
    public ResponseEntity<ImportAnalysisStore.View> resume(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id, @Valid @RequestBody Version input) { return response(store.action(projectId, importId, id, input.version(), "resume")); }
    @PostMapping("/{id}/actions/cancel")
    public ResponseEntity<ImportAnalysisStore.View> cancel(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id, @Valid @RequestBody Version input) { return response(store.action(projectId, importId, id, input.version(), "cancel")); }
    @PostMapping("/{id}/actions/confirm")
    public ResponseEntity<ImportAnalysisStore.View> confirm(@PathVariable UUID projectId, @PathVariable UUID importId, @PathVariable UUID id, @RequestBody ImportAnalysisStore.Confirm input) { return response(store.confirm(projectId, importId, id, input)); }
    private static <T> ResponseEntity<T> response(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value); }
}
