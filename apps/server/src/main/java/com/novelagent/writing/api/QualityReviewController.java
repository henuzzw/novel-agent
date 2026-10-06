package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.application.QualityReviewService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}/quality-reviews")
public class QualityReviewController {
    private final QualityReviewService service;

    public QualityReviewController(QualityReviewService service) { this.service = service; }

    public record GenerateRequest(ModelProvider provider, String instruction) { }

    @GetMapping("/latest")
    public ResponseEntity<QualityReviewResponse> latest(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return service.latest(projectId, chapterNumber).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/actions/generate")
    public ResponseEntity<QualityReviewResponse> generate(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @RequestBody GenerateRequest request) {
        return ResponseEntity.status(201).body(service.generate(projectId, chapterNumber, request.provider(), request.instruction()));
    }

    @PostMapping("/{id}/actions/revise")
    public ResponseEntity<ManuscriptResponse> revise(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @PathVariable UUID id, @Valid @RequestBody ReviseQualityRequest request) {
        return ResponseEntity.status(201).body(service.revise(projectId, chapterNumber, id, request.issueIds(), request.provider(), request.instruction(), request.scope()));
    }
}
