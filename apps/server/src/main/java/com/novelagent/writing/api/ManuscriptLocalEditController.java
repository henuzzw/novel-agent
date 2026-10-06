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

/**
 * 正文局部改写。
 *
 * <p>接收准确选区、源版本和修订授权，返回新正文候选及差异。冲突响应帮助作者刷新来源；不修改选区外文本或直接提交正史。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}/manuscripts/actions/local-edit")
public class ManuscriptLocalEditController {
    private final ManuscriptLocalEditService service;

    public ManuscriptLocalEditController(ManuscriptLocalEditService service) {
        this.service = service;
    }

    /**
     * 保存作者编辑并遵循源版本及授权范围；上游资料改变后，依赖它的旧检查不能继续当作当前依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping
    public ResponseEntity<ManuscriptLocalEditResponse> edit(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @Valid @RequestBody ManuscriptLocalEditRequest request) {
        ManuscriptLocalEditResponse result = service.edit(projectId, chapterNumber, request);
        return ResponseEntity.status(result.manuscript() == null ? 200 : 201).body(result);
    }

    /**
     * 将局部改写的来源失效异常转换为 HTTP 409，附 MANUSCRIPT_LOCAL_EDIT_STALE 错误码，交由界面刷新来源；不自动重试模型。
     *
     * @param failure 本次失败原因或来源冲突信息。
     */
    @ExceptionHandler(ManuscriptLocalEditConflictException.class)
    public ProblemDetail conflict(ManuscriptLocalEditConflictException failure) {
        ProblemDetail detail = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, failure.getMessage());
        detail.setProperty("code", "MANUSCRIPT_LOCAL_EDIT_STALE");
        return detail;
    }
}
