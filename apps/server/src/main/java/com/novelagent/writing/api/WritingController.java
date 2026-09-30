package com.novelagent.writing.api;

import com.novelagent.writing.application.WritingService;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}")
public class WritingController {
    private final WritingService service;
    public WritingController(WritingService service) { this.service = service; }

    @GetMapping("/contracts/latest")
    public ResponseEntity<ChapterContractResponse> latestContract(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return service.latestContract(projectId, chapterNumber).map(value -> ResponseEntity.ok().eTag(tag(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    @PostMapping("/contracts/actions/generate")
    public ResponseEntity<ChapterContractResponse> generateContract(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @RequestBody GenerateWritingRequest request) {
        ChapterContractResponse value = service.generateContract(projectId, chapterNumber, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber + "/contracts/" + value.id()))
                .eTag(tag(value.version())).body(value);
    }
    @PutMapping("/contracts/{id}")
    public ResponseEntity<ChapterContractResponse> updateContract(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match, @RequestBody UpdateChapterContractRequest request) {
        ChapterContractResponse value = service.updateContract(projectId, id, parse(match), request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    @PostMapping("/contracts/{id}/actions/approve")
    public ResponseEntity<ChapterContractResponse> approveContract(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match) {
        ChapterContractResponse value = service.approveContract(projectId, id, parse(match));
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    @GetMapping("/manuscripts/latest")
    public ResponseEntity<ManuscriptResponse> latestManuscript(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return service.latestManuscript(projectId, chapterNumber).map(value -> ResponseEntity.ok().eTag(tag(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    @PostMapping("/manuscripts/actions/generate")
    public ResponseEntity<ManuscriptResponse> generateManuscript(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @RequestBody GenerateWritingRequest request) {
        ManuscriptResponse value = service.generateManuscript(projectId, chapterNumber, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber + "/manuscripts/" + value.id()))
                .eTag(tag(value.version())).body(value);
    }
    @PutMapping("/manuscripts/{id}")
    public ResponseEntity<ManuscriptResponse> updateManuscript(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match, @RequestBody UpdateManuscriptRequest request) {
        ManuscriptResponse value = service.updateManuscript(projectId, id, parse(match), request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    @PostMapping("/manuscripts/{id}/actions/accept")
    public ResponseEntity<ManuscriptResponse> acceptManuscript(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match) {
        ManuscriptResponse value = service.acceptManuscript(projectId, id, parse(match));
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    @GetMapping("/manuscripts/{id}/export")
    public ResponseEntity<byte[]> exportManuscript(@PathVariable UUID projectId, @PathVariable UUID id) {
        byte[] content = service.exportManuscript(projectId, id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=chapter.md")
                .body(content);
    }
    @GetMapping("/reviews/latest")
    public ResponseEntity<ChapterReviewResponse> latestReview(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return service.latestReview(projectId, chapterNumber).map(value -> ResponseEntity.ok().eTag(tag(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    @PostMapping("/reviews/actions/generate")
    public ResponseEntity<ChapterReviewResponse> generateReview(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @RequestBody GenerateWritingRequest request) {
        ChapterReviewResponse value = service.generateReview(projectId, chapterNumber, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber + "/reviews/" + value.id()))
                .eTag(tag(value.version())).body(value);
    }
    @PutMapping("/reviews/{id}")
    public ResponseEntity<ChapterReviewResponse> updateReview(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match, @RequestBody UpdateChapterReviewRequest request) {
        ChapterReviewResponse value = service.updateReview(projectId, id, parse(match), request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    @PostMapping("/reviews/{id}/actions/approve")
    public ResponseEntity<ChapterReviewResponse> approveReview(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match) {
        ChapterReviewResponse value = service.approveReview(projectId, id, parse(match));
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    private static long parse(String value) { return Long.parseLong(value.replace("\"", "").trim()); }
    private static String tag(long value) { return Long.toString(value); }
}
