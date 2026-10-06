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

/**
 * 正文质量检查。
 *
 * <p>查询四维报告，触发独立检查或按选中建议有限修订。表达润色与场景结构修改权限不同，质量分数不是正史审批。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}/quality-reviews")
public class QualityReviewController {
    private final QualityReviewService service;

    public QualityReviewController(QualityReviewService service) { this.service = service; }

    public record GenerateRequest(ModelProvider provider, String instruction) { }

    /**
     * 读取最新保存结果；“最新”不自动表示已发布、已确认或已进入正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    @GetMapping("/latest")
    public ResponseEntity<QualityReviewResponse> latest(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return service.latest(projectId, chapterNumber).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * 读取当前正文与完整检查依据，在事务外生成四维报告，再由存储层复核并保存；正文无需先进入正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/generate")
    public ResponseEntity<QualityReviewResponse> generate(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @RequestBody GenerateRequest request) {
        return ResponseEntity.status(201).body(service.generate(projectId, chapterNumber, request.provider(), request.instruction()));
    }

    /**
     * 核对当前报告及作者所选问题，按 EXPRESSION_ONLY 或明确 SCENE_STRUCTURE 范围构造修订草稿，再复核来源保存，不自动接受。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/{id}/actions/revise")
    public ResponseEntity<ManuscriptResponse> revise(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @PathVariable UUID id, @Valid @RequestBody ReviseQualityRequest request) {
        return ResponseEntity.status(201).body(service.revise(projectId, chapterNumber, id, request.issueIds(), request.provider(), request.instruction(), request.scope()));
    }
}
