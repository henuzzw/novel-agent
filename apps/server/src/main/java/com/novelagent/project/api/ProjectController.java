package com.novelagent.project.api;

import com.novelagent.project.application.ProjectService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(@Valid @RequestBody CreateProjectRequest request) {
        ProjectResponse project = projectService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + project.id()))
                .eTag(Long.toString(project.version()))
                .body(project);
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.list();
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> get(@PathVariable UUID projectId) {
        ProjectResponse project = projectService.get(projectId);
        return ResponseEntity.ok()
                .eTag(Long.toString(project.version()))
                .body(project);
    }

    @PutMapping("/{projectId}/creative-intent")
    public ResponseEntity<ProjectResponse> updateCreativeIntent(
            @PathVariable UUID projectId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody CreativeIntentRequest request) {
        long expectedVersion = parseEtag(ifMatch);
        ProjectResponse project = projectService.updateCreativeIntent(projectId, expectedVersion, request);
        long intentVersion = project.creativeIntent().version();
        return ResponseEntity.ok()
                .eTag(Long.toString(intentVersion))
                .body(project);
    }

    private static long parseEtag(String value) {
        return Long.parseLong(value.replace("\"", "").trim());
    }
}

