package com.novelagent.ingest.api;

import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.ingest.application.ImportedPlanningService;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/imports")
public class WorkImportController {
    private final WorkImportService service;
    private final ImportedPlanningService planning;

    public WorkImportController(WorkImportService service, ImportedPlanningService planning) {
        this.service = service;
        this.planning = planning;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkImportResponse> upload(
            @PathVariable UUID projectId,
            @RequestPart("file") MultipartFile file) {
        WorkImportResponse result = service.upload(projectId, file);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/imports/" + result.id()))
                .body(result);
    }

    @GetMapping
    public List<WorkImportResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    @GetMapping("/{importId}")
    public WorkImportResponse get(@PathVariable UUID projectId, @PathVariable UUID importId) {
        return service.get(projectId, importId);
    }

    @PostMapping("/{importId}/actions/confirm")
    public WorkImportResponse confirm(@PathVariable UUID projectId, @PathVariable UUID importId) {
        return service.confirm(projectId, importId);
    }

    @PostMapping("/{importId}/actions/reverse-plan")
    public ReversePlanResponse reversePlan(@PathVariable UUID projectId, @PathVariable UUID importId,
            @Valid @RequestBody ReversePlanRequest request) {
        return planning.generate(projectId, importId, request);
    }

    @GetMapping("/{importId}/source")
    public ResponseEntity<byte[]> source(@PathVariable UUID projectId, @PathVariable UUID importId) {
        WorkImportService.SourceFile source = service.source(projectId, importId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(source.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(source.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(source.content());
    }
}
