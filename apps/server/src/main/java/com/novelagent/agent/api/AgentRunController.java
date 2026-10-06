package com.novelagent.agent.api;

import com.novelagent.agent.application.AgentRunQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs")
public class AgentRunController {
    private final AgentRunQueryService service;

    public AgentRunController(AgentRunQueryService service) { this.service = service; }

    @GetMapping
    public List<AgentRunResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    @GetMapping("/summary")
    public AgentRunSummary summary(@PathVariable UUID projectId) {
        return service.summary(projectId);
    }

    @GetMapping("/{runId}/prompt")
    public ResponseEntity<AgentRunPromptResponse> prompt(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return service.prompt(projectId, runId)
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{runId}/request-snapshot")
    public ResponseEntity<AgentRunResponse.RequestSnapshotResponse> requestSnapshot(
            @PathVariable UUID projectId, @PathVariable UUID runId) {
        return service.requestSnapshot(projectId, runId)
                .map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
