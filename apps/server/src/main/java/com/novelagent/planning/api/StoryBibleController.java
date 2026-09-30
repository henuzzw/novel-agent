package com.novelagent.planning.api;

import com.novelagent.planning.application.StoryBibleService;
import jakarta.validation.Valid;
import java.net.URI;
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
@RequestMapping("/api/v1/projects/{projectId}/story-bibles")
public class StoryBibleController {
    private final StoryBibleService service;

    public StoryBibleController(StoryBibleService service) { this.service = service; }

    @GetMapping("/latest")
    public ResponseEntity<StoryBibleResponse> latest(@PathVariable UUID projectId) {
        return service.latest(projectId).map(value -> ResponseEntity.ok().eTag(Long.toString(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/actions/generate")
    public ResponseEntity<StoryBibleResponse> generate(@PathVariable UUID projectId,
            @Valid @RequestBody GenerateStoryBibleRequest request) {
        StoryBibleResponse value = service.generate(projectId, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/story-bibles/" + value.id()))
                .eTag(Long.toString(value.version())).body(value);
    }

    @PutMapping("/{versionId}")
    public ResponseEntity<StoryBibleResponse> update(@PathVariable UUID projectId, @PathVariable UUID versionId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody UpdateStoryBibleRequest request) {
        StoryBibleResponse value = service.update(projectId, versionId, parseEtag(ifMatch), request.content());
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }

    @PostMapping("/{versionId}/actions/publish")
    public ResponseEntity<StoryBibleResponse> publish(@PathVariable UUID projectId, @PathVariable UUID versionId,
            @RequestHeader("If-Match") String ifMatch) {
        StoryBibleResponse value = service.publish(projectId, versionId, parseEtag(ifMatch));
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }

    private static long parseEtag(String value) { return Long.parseLong(value.replace("\"", "").trim()); }
}
