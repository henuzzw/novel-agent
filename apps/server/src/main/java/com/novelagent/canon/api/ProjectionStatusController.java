package com.novelagent.canon.api;

import com.novelagent.canon.application.ProjectionStatusService;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/projection-status")
public class ProjectionStatusController {
    private final ProjectionStatusService service;

    public ProjectionStatusController(ProjectionStatusService service) { this.service = service; }

    @GetMapping
    public ProjectionStatus status(@PathVariable UUID projectId) {
        return service.status(projectId);
    }
}
