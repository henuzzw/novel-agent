package com.novelagent.writing.application;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.GenerateStylePreviewRequest;
import com.novelagent.writing.api.WritingStylePreviewResponse;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class WritingStylePreviewService {
    private final WritingContextService contexts;
    private final WritingGenerationWorkflow workflow;
    private final WritingStyleService styles;

    public WritingStylePreviewService(WritingContextService contexts, WritingGenerationWorkflow workflow, WritingStyleService styles) {
        this.contexts = contexts;
        this.workflow = workflow;
        this.styles = styles;
    }

    public WritingStylePreviewResponse generate(UUID projectId, GenerateStylePreviewRequest request) {
        WritingContextService.Context context = contexts.previewContext(projectId, request.outlineVersionId());
        WritingBasisSnapshot basis = WritingBasisSnapshot.capture(context);
        long sourceVersion = context.outline().getRowVersion();
        WritingChecks.check(sourceVersion, request.expectedOutlineVersion());
        var profile = styles.resolveProfile(request.profile());
        WritingStylePreviewContent content = workflow.generateStylePreview(projectId, context.bible().getContent(),
                context.arc(), context.chapter(), profile, request.provider(), request.effectiveTargetWords(),
                context.instructionWithPreparation(WritingChecks.normalize(request.instruction())));
        WritingContextService.Context current = contexts.previewContext(projectId, request.outlineVersionId());
        WritingChecks.check(current.outline().getRowVersion(), sourceVersion);
        basis.requireUnchanged(current);
        return new WritingStylePreviewResponse(context.outline().getId(), sourceVersion,
                context.outline().getGenerationNumber(), context.bible().getId(), profile, request.provider(),
                request.effectiveTargetWords(), request.provider() == ModelProvider.LOCAL_TEMPLATE ? "TEMPLATE" : "MODEL",
                content);
    }

}
