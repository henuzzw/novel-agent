package com.novelagent.planning.api;

import com.novelagent.planning.application.StoryDirectionService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/story-directions")
public class StoryDirectionController {

    private final StoryDirectionService storyDirectionService;

    public StoryDirectionController(StoryDirectionService storyDirectionService) {
        this.storyDirectionService = storyDirectionService;
    }

    @PostMapping("/actions/generate")
    public ResponseEntity<StoryDirectionSetResponse> generate(
            @PathVariable UUID projectId,
            @Valid @RequestBody GenerateStoryDirectionsRequest request) {
        StoryDirectionSetResponse response = storyDirectionService.generate(projectId, request);
        return ResponseEntity.created(URI.create(
                        "/api/v1/projects/" + projectId + "/story-directions/" + response.id()))
                .eTag(Long.toString(response.version()))
                .body(response);
    }

    @GetMapping("/latest")
    public ResponseEntity<StoryDirectionSetResponse> latest(@PathVariable UUID projectId) {
        return storyDirectionService.latest(projectId)
                .map(response -> ResponseEntity.ok()
                        .eTag(Long.toString(response.version()))
                        .body(response))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/{setId}/actions/select")
    public ResponseEntity<StoryDirectionSetResponse> select(
            @PathVariable UUID projectId,
            @PathVariable UUID setId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody SelectStoryDirectionRequest request) {
        StoryDirectionSetResponse response = storyDirectionService.select(
                projectId, setId, parseEtag(ifMatch), request.candidateId());
        return ResponseEntity.ok()
                .eTag(Long.toString(response.version()))
                .body(response);
    }

    private static long parseEtag(String value) {
        return Long.parseLong(value.replace("\"", "").trim());
    }
}
