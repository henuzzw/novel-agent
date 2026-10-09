package com.novelagent.canon.api;

import com.novelagent.canon.application.CanonPublicationService;
import com.novelagent.canon.application.PublishedMemoryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}/canon-commits")
public class CanonPublicationController {
    private final CanonPublicationService publication;
    private final PublishedMemoryService memory;
    public CanonPublicationController(CanonPublicationService publication, PublishedMemoryService memory) {
        this.publication = publication; this.memory = memory;
    }
    @PostMapping("/actions/publish")
    public CanonCommitResponse publish(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @RequestBody PublishManuscriptRequest request) { return publication.publish(projectId, chapterNumber, request); }
    @GetMapping("/memory")
    public PublishedMemoryService.View memory(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return memory.status(projectId, chapterNumber);
    }
    public record RetryRequest(long version) { }
    @PostMapping("/{commitId}/memory/actions/retry")
    public PublishedMemoryService.View retry(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @PathVariable UUID commitId, @RequestBody RetryRequest request) {
        return memory.retry(projectId, chapterNumber, commitId, request.version());
    }
    @PostMapping("/{commitId}/memory/actions/confirm")
    public PublishedMemoryService.View confirm(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @PathVariable UUID commitId, @RequestBody PublishedMemoryService.Confirmation request) {
        return memory.confirm(projectId, chapterNumber, commitId, request);
    }
}
