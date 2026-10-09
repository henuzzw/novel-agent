package com.novelagent.project.api;

import com.novelagent.project.application.StorySourceProjectService;
import com.novelagent.project.domain.CreativeStrategy;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects")
public class StorySourceProjectController {
    private final StorySourceProjectService service;
    public StorySourceProjectController(StorySourceProjectService service) { this.service = service; }
    @PostMapping(value = "/from-story", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProjectResponse> create(@RequestParam(required = false) String name,
            @RequestParam(required = false) CreativeStrategy creativeStrategy,
            @RequestPart(required = false) MultipartFile file, @RequestParam(required = false) String text) {
        var project = service.create(name, creativeStrategy, file, text);
        return ResponseEntity.created(java.net.URI.create("/api/v1/projects/" + project.id())).body(project);
    }
}
