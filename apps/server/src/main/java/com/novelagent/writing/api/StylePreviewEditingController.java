package com.novelagent.writing.api;

import com.novelagent.writing.application.StylePreviewEditingService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/writing-style")
public class StylePreviewEditingController {
    private final StylePreviewEditingService editing;

    public StylePreviewEditingController(StylePreviewEditingService editing) {
        this.editing = editing;
    }

    @PostMapping("/actions/check-preview")
    public StylePreviewReviewResponse check(@PathVariable UUID projectId,
            @Valid @RequestBody CheckStylePreviewRequest request) {
        return editing.check(projectId, request);
    }

    @PostMapping("/preview-reviews/{id}/actions/revise")
    public WritingStylePreviewResponse revise(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody ReviseStylePreviewRequest request) {
        return editing.revise(projectId, id, request);
    }
}
