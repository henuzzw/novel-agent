package com.novelagent.agent.api;

import com.novelagent.agent.application.GenerationControlRegistry;
import com.novelagent.project.application.ProjectAccessService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class GenerationControlController {
    private final ProjectAccessService access;
    private final GenerationControlRegistry controls;

    public GenerationControlController(ProjectAccessService access, GenerationControlRegistry controls) {
        this.access = access;
        this.controls = controls;
    }

    @PostMapping("/generation-requests/{requestId}/actions/stop")
    public StopResponse stopRequest(@PathVariable UUID projectId, @PathVariable UUID requestId) {
        access.requireOwnedProject(projectId);
        controls.stopRequest(projectId, requestId);
        return new StopResponse("STOP_REQUESTED");
    }

    @PostMapping("/agent-runs/{runId}/actions/stop")
    public StopResponse stopRun(@PathVariable UUID projectId, @PathVariable UUID runId) {
        access.requireOwnedProject(projectId);
        controls.stopRun(projectId, runId);
        return new StopResponse("STOP_REQUESTED");
    }

    public record StopResponse(String status) { }
}
