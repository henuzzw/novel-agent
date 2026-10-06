package com.novelagent.agent.api;

import com.novelagent.agent.application.AutomationService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/automation-runs")
public class AutomationController {
    private final AutomationService service;

    public AutomationController(AutomationService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<AutomationRunResponse> create(@PathVariable UUID projectId,
            @RequestHeader("Idempotency-Key") UUID requestKey,
            @Valid @RequestBody CreateAutomationRunRequest request) {
        AutomationRunResponse run = service.create(projectId, requestKey, request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/projects/" + projectId
                + "/automation-runs/" + run.id())).body(run);
    }

    @GetMapping
    public List<AutomationRunResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    @GetMapping("/{id}")
    public AutomationRunResponse get(@PathVariable UUID projectId, @PathVariable UUID id) {
        return service.get(projectId, id);
    }

    @PostMapping("/{id}/actions/resume")
    public ResponseEntity<AutomationRunResponse> resume(@PathVariable UUID projectId, @PathVariable UUID id) {
        return ResponseEntity.accepted().body(service.resume(projectId, id));
    }

    @PostMapping("/{id}/actions/cancel")
    public AutomationRunResponse cancel(@PathVariable UUID projectId, @PathVariable UUID id) {
        return service.cancel(projectId, id);
    }
}
