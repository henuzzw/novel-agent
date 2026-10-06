package com.novelagent.writing.api;

import com.novelagent.writing.application.ManuscriptLocalEditConflictException;
import com.novelagent.writing.application.ManuscriptLocalEditService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}/manuscripts/actions/local-edit")
public class ManuscriptLocalEditController {
    private final ManuscriptLocalEditService service;

    public ManuscriptLocalEditController(ManuscriptLocalEditService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ManuscriptLocalEditResponse> edit(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @Valid @RequestBody ManuscriptLocalEditRequest request) {
        ManuscriptLocalEditResponse result = service.edit(projectId, chapterNumber, request);
        return ResponseEntity.status(result.manuscript() == null ? 200 : 201).body(result);
    }

    @ExceptionHandler(ManuscriptLocalEditConflictException.class)
    public ProblemDetail conflict(ManuscriptLocalEditConflictException failure) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage());
        detail.setProperty("code", "MANUSCRIPT_LOCAL_EDIT_STALE");
        return detail;
    }
}
