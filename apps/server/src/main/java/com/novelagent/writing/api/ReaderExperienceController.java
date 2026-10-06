package com.novelagent.writing.api;

import com.novelagent.writing.application.ReaderExperienceService;
import com.novelagent.writing.domain.ReaderExperienceEntry;
import com.novelagent.writing.domain.ReaderExperienceManuscript;
import com.novelagent.writing.domain.ReaderExperienceMemory;
import com.novelagent.writing.domain.ReaderExperiencePlanInput;
import com.novelagent.writing.domain.ReaderExperienceSource;
import com.novelagent.writing.domain.ReaderExperienceSubmission;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/reader-experiences")
public class ReaderExperienceController {
    private final ReaderExperienceService service;

    public ReaderExperienceController(ReaderExperienceService service) { this.service = service; }

    @GetMapping
    public List<ReaderExperienceEntry> list(@PathVariable UUID projectId) { return service.list(projectId); }

    @GetMapping("/{id}")
    public ReaderExperienceEntry get(@PathVariable UUID projectId, @PathVariable UUID id) { return service.get(projectId, id); }

    @GetMapping("/sources")
    public List<ReaderExperienceManuscript> sources(@PathVariable UUID projectId) { return service.sources(projectId); }

    @GetMapping("/sources/{manuscriptId}")
    public ReaderExperienceSource source(@PathVariable UUID projectId, @PathVariable UUID manuscriptId) {
        return service.source(projectId, manuscriptId);
    }

    @GetMapping("/memory")
    public ReaderExperienceMemory memory(@PathVariable UUID projectId) { return service.memory(projectId); }

    @PostMapping
    public ResponseEntity<ReaderExperienceEntry> create(@PathVariable UUID projectId, @RequestBody ReaderExperiencePlanInput input) {
        return ResponseEntity.status(201).body(service.create(projectId, input));
    }

    @PutMapping("/{id}")
    public ReaderExperienceEntry update(@PathVariable UUID projectId, @PathVariable UUID id, @RequestBody ReaderExperiencePlanInput input) {
        return service.update(projectId, id, input);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestParam long expectedVersion, @RequestParam UUID requestId) {
        service.delete(projectId, id, expectedVersion, requestId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/events")
    public ReaderExperienceEntry submit(@PathVariable UUID projectId, @PathVariable UUID id, @RequestBody ReaderExperienceSubmission input) {
        return service.submit(projectId, id, input);
    }
}
