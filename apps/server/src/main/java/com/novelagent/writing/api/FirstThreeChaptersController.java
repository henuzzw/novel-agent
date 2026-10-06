package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.application.FirstThreeChaptersService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/opening-review")
public class FirstThreeChaptersController {
    private final FirstThreeChaptersService service;
    public FirstThreeChaptersController(FirstThreeChaptersService service) { this.service = service; }
    public record CheckRequest(List<UUID> manuscriptIds, ModelProvider provider, String instruction,
            String expectedFingerprint, int maxInputTokens) { }
    @GetMapping
    public FirstThreeChaptersService.View get(@PathVariable UUID projectId,
            @RequestParam(required = false) List<UUID> manuscriptIds,
            @RequestParam(defaultValue = "LOCAL_TEMPLATE") ModelProvider provider, @RequestParam(defaultValue = "") String instruction) {
        return service.get(projectId, manuscriptIds, provider, instruction);
    }
    @PostMapping("/actions/check")
    public ResponseEntity<FirstThreeChaptersService.Report> check(@PathVariable UUID projectId, @RequestBody CheckRequest request) {
        return ResponseEntity.status(201).body(service.check(projectId, request.manuscriptIds(), request.provider(),
                request.instruction(), request.expectedFingerprint(), request.maxInputTokens()));
    }
}
