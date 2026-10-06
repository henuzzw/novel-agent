package com.novelagent.writing.api;

import com.novelagent.writing.application.WritingService;
import java.net.URI;
import java.util.UUID;
import java.util.List;
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
    @GetMapping("/contracts")
    public List<ChapterContractVersionSummaryResponse> contractVersions(@PathVariable UUID projectId,
            @PathVariable int chapterNumber) {
        return service.contractVersions(projectId, chapterNumber);
    }
    @GetMapping("/contracts/{id}")
    public ChapterContractResponse contractVersion(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @PathVariable UUID id) {
        return service.contractVersion(projectId, chapterNumber, id);
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
    @GetMapping("/contract-reviews/latest")
    public ResponseEntity<ChapterContractReviewResponse> latestContractReview(@PathVariable UUID projectId,
            @PathVariable int chapterNumber) {
        return service.latestContractReview(projectId, chapterNumber)
                .map(value -> ResponseEntity.ok().eTag(tag(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    @PostMapping("/contract-reviews/actions/generate")
    public ResponseEntity<ChapterContractReviewResponse> generateContractReview(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @RequestBody GenerateWritingRequest request) {
        ChapterContractReviewResponse value = service.generateContractReview(projectId, chapterNumber, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber
                + "/contract-reviews/" + value.id())).eTag(tag(value.version())).body(value);
    }
    @PostMapping("/contract-reviews/{id}/actions/approve")
    public ResponseEntity<ChapterContractReviewResponse> approveContractReview(@PathVariable UUID projectId,
            @PathVariable UUID id, @RequestHeader("If-Match") String match,
            @RequestBody(required = false) UpdateChapterContractReviewRequest request) {
        ChapterContractReviewResponse value = service.approveContractReview(projectId, id, parse(match),
                request == null ? null : request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    @GetMapping("/manuscripts/latest")
    public ResponseEntity<ManuscriptResponse> latestManuscript(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return service.latestManuscript(projectId, chapterNumber).map(value -> ResponseEntity.ok().eTag(tag(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    @GetMapping("/manuscripts")
    public List<ManuscriptVersionSummaryResponse> manuscriptVersions(@PathVariable UUID projectId,
            @PathVariable int chapterNumber) {
        return service.manuscriptVersions(projectId, chapterNumber);
    }
    @GetMapping("/manuscripts/{id}")
    public ManuscriptResponse manuscriptVersion(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @PathVariable UUID id) {
        return service.manuscriptVersion(projectId, chapterNumber, id);
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
    @PostMapping("/manuscripts/{id}/actions/create-revision")
    public ResponseEntity<ManuscriptResponse> createManuscriptRevision(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @PathVariable UUID id, @RequestHeader("If-Match") String match) {
        ManuscriptResponse value = service.createManuscriptRevision(projectId, chapterNumber, id, parse(match));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber
                + "/manuscripts/" + value.id())).eTag(tag(value.version())).body(value);
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
            @RequestHeader("If-Match") String match, @RequestBody(required = false) UpdateChapterReviewRequest request) {
        ChapterReviewResponse value = service.approveReview(projectId, id, parse(match), request == null ? null : request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    @PostMapping("/reviews/{id}/actions/return-to-writing")
    public ResponseEntity<ManuscriptResponse> returnReviewToWriting(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @PathVariable UUID id,
            @RequestHeader("If-Match") String match, @RequestBody ReturnReviewRequest request) {
        ManuscriptResponse value = service.returnReviewToWriting(projectId, chapterNumber, id, parse(match), request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber
                + "/manuscripts/" + value.id())).eTag(tag(value.version())).body(value);
    }
    private static long parse(String value) { return Long.parseLong(value.replace("\"", "").trim()); }
    private static String tag(long value) { return Long.toString(value); }
}
