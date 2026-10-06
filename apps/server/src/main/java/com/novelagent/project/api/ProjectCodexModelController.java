package com.novelagent.project.api;

import com.novelagent.project.application.ProjectCodexModelService;
import com.novelagent.project.domain.CodexModelChoice;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/settings/codex-model")
public class ProjectCodexModelController {
    private final ProjectCodexModelService service;

    public ProjectCodexModelController(ProjectCodexModelService service) {
        this.service = service;
    }

    @GetMapping
    public CodexModelChoice get(@PathVariable UUID projectId) {
        return service.get(projectId);
    }

    @PutMapping
    public CodexModelChoice update(@PathVariable UUID projectId, @RequestBody CodexModelChoice choice) {
        return service.update(projectId, choice);
    }
}
