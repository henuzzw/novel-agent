package com.novelagent.writing.api;

import com.novelagent.writing.application.StylePreviewEditingService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 试写检查与修订。
 *
 * <p>对开头样例独立检查，再按作者选中建议进行一次有限修订。原样例和报告保留，不自动采用风格或写入正式正文。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/writing-style")
public class StylePreviewEditingController {
    private final StylePreviewEditingService editing;

    public StylePreviewEditingController(StylePreviewEditingService editing) {
        this.editing = editing;
    }

    /**
     * 按不可变样例与指定大纲建立快照，独立检查四维并核验连续原文证据，保存私有报告，不修改样例。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/check-preview")
    public StylePreviewReviewResponse check(@PathVariable UUID projectId,
            @Valid @RequestBody CheckStylePreviewRequest request) {
        return editing.check(projectId, request);
    }

    /**
     * 锁内认领报告的一次修订尝试，仅处理服务端报告中选中的问题；事务外生成后复核依据，返回候选不采用风格。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/preview-reviews/{id}/actions/revise")
    public WritingStylePreviewResponse revise(@PathVariable UUID projectId, @PathVariable UUID id,
            @Valid @RequestBody ReviseStylePreviewRequest request) {
        return editing.revise(projectId, id, request);
    }
}
