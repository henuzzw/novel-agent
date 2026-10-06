package com.novelagent.writing.application;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.GenerateStylePreviewRequest;
import com.novelagent.writing.api.WritingStylePreviewResponse;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 第一章风格试写。
 *
 * <p>按指定已保存大纲第一章和候选风格事务外生成开头样例，返回前复核依据。试写不是完整章节，也不保存正式正文、应用风格或提交正史。</p>
 */
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

    /**
     * 核对指定大纲行版本并解析候选技法，在事务外试写第一章开头；返回前核对大纲及主要创作依据，结果只作试写展示。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
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
