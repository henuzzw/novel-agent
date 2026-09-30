package com.novelagent.planning.api;

import com.novelagent.planning.application.OutlineService;
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
@RequestMapping("/api/v1/projects/{projectId}/outlines")
public class OutlineController {
    private final OutlineService service;
    public OutlineController(OutlineService service) { this.service = service; }

    @GetMapping("/latest")
    public ResponseEntity<OutlineResponse> latest(@PathVariable UUID projectId) {
        return service.latest(projectId).map(value -> ResponseEntity.ok().eTag(Long.toString(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    @GetMapping("/current")
    public ResponseEntity<OutlineResponse> current(@PathVariable UUID projectId) {
        return service.current(projectId)
                .map(value -> ResponseEntity.ok().eTag(Long.toString(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    @GetMapping
    public List<OutlineVersionSummaryResponse> versions(@PathVariable UUID projectId) {
        return service.versions(projectId);
    }
    @GetMapping("/{outlineId}")
    public OutlineResponse version(@PathVariable UUID projectId, @PathVariable UUID outlineId) {
        return service.version(projectId, outlineId);
    }
    @PostMapping("/actions/generate")
    public ResponseEntity<OutlineResponse> generate(@PathVariable UUID projectId,
            @Valid @RequestBody GenerateOutlineRequest request) {
        OutlineResponse value = service.generate(projectId, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/outlines/" + value.id()))
                .eTag(Long.toString(value.version())).body(value);
    }
    @PutMapping("/{outlineId}")
    public ResponseEntity<OutlineResponse> update(@PathVariable UUID projectId, @PathVariable UUID outlineId,
            @RequestHeader("If-Match") String ifMatch, @Valid @RequestBody UpdateOutlineRequest request) {
        OutlineResponse value = service.update(projectId, outlineId, parse(ifMatch), request.content());
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }
    @PostMapping("/{outlineId}/actions/publish")
    public ResponseEntity<OutlineResponse> publish(@PathVariable UUID projectId, @PathVariable UUID outlineId,
            @RequestHeader("If-Match") String ifMatch) {
        OutlineResponse value = service.publish(projectId, outlineId, parse(ifMatch));
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }
    private static long parse(String value) { return Long.parseLong(value.replace("\"", "").trim()); }
}
