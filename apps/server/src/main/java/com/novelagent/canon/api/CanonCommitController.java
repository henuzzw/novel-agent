package com.novelagent.canon.api;

import com.novelagent.canon.application.CanonCommitService;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}/canon-commits")
public class CanonCommitController {
    private final CanonCommitService service;

    public CanonCommitController(CanonCommitService service) {
        this.service = service;
    }

    @GetMapping("/status")
    public CanonCommitStatusResponse status(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        var current = service.currentCommit(projectId, chapterNumber);
        return current == null ? new CanonCommitStatusResponse(false, null, null, 0)
                : new CanonCommitStatusResponse(true, current.getId(), current.getManuscriptVersionId(),
                        current.getCanonVersion());
    }

    @PostMapping
    public ResponseEntity<CanonCommitResponse> commit(
            @PathVariable UUID projectId,
            @PathVariable int chapterNumber,
            @RequestBody CommitCanonRequest request) {
        CanonCommitResponse response = service.commit(projectId, chapterNumber, request);
        URI location = URI.create("/api/v1/projects/" + projectId + "/canon-commits/" + response.id());
        return ResponseEntity.created(location).body(response);
    }

    @PostMapping("/actions/replace")
    public CanonCommitResponse replace(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @RequestBody ReplaceCanonRequest request) {
        return service.replace(projectId, chapterNumber, request);
    }
}
