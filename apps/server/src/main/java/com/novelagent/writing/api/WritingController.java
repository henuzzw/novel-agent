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

/**
 * 章节写作。
 *
 * <p>统一提供合同、合同审阅、正文和审稿的版本操作及导出。确认合同、确认正文、确认审稿和提交正史是不同门禁，不能相互代替。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}")
public class WritingController {
    private final WritingService service;
    public WritingController(WritingService service) { this.service = service; }

    /**
     * 读取本章最新保存合同，可能仍是草稿；不代表已通过审阅或作者确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    @GetMapping("/contracts/latest")
    public ResponseEntity<ChapterContractResponse> latestContract(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return service.latestContract(projectId, chapterNumber).map(value -> ResponseEntity.ok().eTag(tag(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    /**
     * 列出本章历史合同版本摘要，供作者显式选择生成基准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/contracts")
    public List<ChapterContractVersionSummaryResponse> contractVersions(@PathVariable UUID projectId,
            @PathVariable int chapterNumber) {
        return service.contractVersions(projectId, chapterNumber);
    }
    /**
     * 按项目、章号和版本 ID 读取指定合同，避免把其他章节版本用作当前依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @GetMapping("/contracts/{id}")
    public ChapterContractResponse contractVersion(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @PathVariable UUID id) {
        return service.contractVersion(projectId, chapterNumber, id);
    }
    /**
     * 根据当前发布规划及记忆生成或有限调整合同草稿，模型返回后核对主要来源；不自动审阅或确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/contracts/actions/generate")
    public ResponseEntity<ChapterContractResponse> generateContract(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @RequestBody GenerateWritingRequest request) {
        ChapterContractResponse value = service.generateContract(projectId, chapterNumber, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber + "/contracts/" + value.id()))
                .eTag(tag(value.version())).body(value);
    }
    /**
     * 按合同当前状态和行版本保存作者编辑；修改后的合同不能沿用不匹配的旧审阅批准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param match If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PutMapping("/contracts/{id}")
    public ResponseEntity<ChapterContractResponse> updateContract(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match, @RequestBody UpdateChapterContractRequest request) {
        ChapterContractResponse value = service.updateContract(projectId, id, parse(match), request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    /**
     * 核对最新合同、最新已确认审阅及来源行版本后确认合同，为正式正文提供依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param match If-Match 请求头中的预期编辑行版本。
     */
    @PostMapping("/contracts/{id}/actions/approve")
    public ResponseEntity<ChapterContractResponse> approveContract(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match) {
        ChapterContractResponse value = service.approveContract(projectId, id, parse(match));
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    /**
     * 读取本章最新合同审阅报告；是否可用于批准合同还需核对来源版本和状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    @GetMapping("/contract-reviews/latest")
    public ResponseEntity<ChapterContractReviewResponse> latestContractReview(@PathVariable UUID projectId,
            @PathVariable int chapterNumber) {
        return service.latestContractReview(projectId, chapterNumber)
                .map(value -> ResponseEntity.ok().eTag(tag(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    /**
     * 独立审阅当前大纲下的合同草稿，保存与合同 ID 及行版本绑定的报告，不直接批准合同。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/contract-reviews/actions/generate")
    public ResponseEntity<ChapterContractReviewResponse> generateContractReview(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @RequestBody GenerateWritingRequest request) {
        ChapterContractReviewResponse value = service.generateContractReview(projectId, chapterNumber, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber
                + "/contract-reviews/" + value.id())).eTag(tag(value.version())).body(value);
    }
    /**
     * 保存作者对审阅问题的处理并确认报告；来源合同或最新报告变化时拒绝使用旧结果。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param match If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/contract-reviews/{id}/actions/approve")
    public ResponseEntity<ChapterContractReviewResponse> approveContractReview(@PathVariable UUID projectId,
            @PathVariable UUID id, @RequestHeader("If-Match") String match,
            @RequestBody(required = false) UpdateChapterContractReviewRequest request) {
        ChapterContractReviewResponse value = service.approveContractReview(projectId, id, parse(match),
                request == null ? null : request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    /**
     * 读取本章最新保存正文并按当前人物名称渲染，最新草稿不等于作者接受或有效正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    @GetMapping("/manuscripts/latest")
    public ResponseEntity<ManuscriptResponse> latestManuscript(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return service.latestManuscript(projectId, chapterNumber).map(value -> ResponseEntity.ok().eTag(tag(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    /**
     * 列出本章正文版本摘要，供作者选择查看或修订基准，不切换有效正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/manuscripts")
    public List<ManuscriptVersionSummaryResponse> manuscriptVersions(@PathVariable UUID projectId,
            @PathVariable int chapterNumber) {
        return service.manuscriptVersions(projectId, chapterNumber);
    }
    /**
     * 按项目、章号及版本 ID 读取指定正文，保留其合同来源、基准稿与状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @GetMapping("/manuscripts/{id}")
    public ManuscriptResponse manuscriptVersion(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @PathVariable UUID id) {
        return service.manuscriptVersion(projectId, chapterNumber, id);
    }
    /**
     * 依据本章已确认且属于当前大纲的合同生成一份新正文草稿，复核上游来源后保存，不直接确认或提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/manuscripts/actions/generate")
    public ResponseEntity<ManuscriptResponse> generateManuscript(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @RequestBody GenerateWritingRequest request) {
        ManuscriptResponse value = service.generateManuscript(projectId, chapterNumber, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber + "/manuscripts/" + value.id()))
                .eTag(tag(value.version())).body(value);
    }
    /**
     * 按行版本保存作者对可编辑正文的修改，保持该版本的业务状态约束。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param match If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PutMapping("/manuscripts/{id}")
    public ResponseEntity<ManuscriptResponse> updateManuscript(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match, @RequestBody UpdateManuscriptRequest request) {
        ManuscriptResponse value = service.updateManuscript(projectId, id, parse(match), request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    /**
     * 将当前作者已确认正文复制为人工修订草稿，原稿保留且新稿记录基准来源；不调用模型。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param match If-Match 请求头中的预期编辑行版本。
     */
    @PostMapping("/manuscripts/{id}/actions/create-revision")
    public ResponseEntity<ManuscriptResponse> createManuscriptRevision(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @PathVariable UUID id, @RequestHeader("If-Match") String match) {
        ManuscriptResponse value = service.createManuscriptRevision(projectId, chapterNumber, id, parse(match));
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber
                + "/manuscripts/" + value.id())).eTag(tag(value.version())).body(value);
    }
    /**
     * 由作者显式确认正文版本；这里只改变正文接受状态，不抽取事实或提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param match If-Match 请求头中的预期编辑行版本。
     */
    @PostMapping("/manuscripts/{id}/actions/accept")
    public ResponseEntity<ManuscriptResponse> acceptManuscript(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match) {
        ManuscriptResponse value = service.acceptManuscript(projectId, id, parse(match));
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    /**
     * 以当前人物显示名称导出选定正文的 Markdown 内容，不修改正文或业务状态。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @GetMapping("/manuscripts/{id}/export")
    public ResponseEntity<byte[]> exportManuscript(@PathVariable UUID projectId, @PathVariable UUID id) {
        byte[] content = service.exportManuscript(projectId, id).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=chapter.md")
                .body(content);
    }
    /**
     * 读取本章最新审稿结果，实际正史提交仍要求报告与确认正文匹配并经作者确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    @GetMapping("/reviews/latest")
    public ResponseEntity<ChapterReviewResponse> latestReview(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return service.latestReview(projectId, chapterNumber).map(value -> ResponseEntity.ok().eTag(tag(value.version())).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
    /**
     * 审查作者确认正文的一致性并提取候选事实，报告保存后由作者处理问题及事实决定，不自动提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/reviews/actions/generate")
    public ResponseEntity<ChapterReviewResponse> generateReview(@PathVariable UUID projectId,
            @PathVariable int chapterNumber, @RequestBody GenerateWritingRequest request) {
        ChapterReviewResponse value = service.generateReview(projectId, chapterNumber, request);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/chapters/" + chapterNumber + "/reviews/" + value.id()))
                .eTag(tag(value.version())).body(value);
    }
    /**
     * 按审稿行版本保存问题处理与候选事实决定，不在此物化事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param match If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PutMapping("/reviews/{id}")
    public ResponseEntity<ChapterReviewResponse> updateReview(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match, @RequestBody UpdateChapterReviewRequest request) {
        ChapterReviewResponse value = service.updateReview(projectId, id, parse(match), request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    /**
     * 确认已处理的审稿结果，阻断问题及来源约束由审稿领域规则检查；确认报告不等于正史提交。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param match If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/reviews/{id}/actions/approve")
    public ResponseEntity<ChapterReviewResponse> approveReview(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestHeader("If-Match") String match, @RequestBody(required = false) UpdateChapterReviewRequest request) {
        ChapterReviewResponse value = service.approveReview(projectId, id, parse(match), request == null ? null : request.content());
        return ResponseEntity.ok().eTag(tag(value.version())).body(value);
    }
    /**
     * 基于审稿所引用的正文及作者选定建议生成返工草稿，保留原确认稿；修改范围服从作者授权。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param match If-Match 请求头中的预期编辑行版本。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
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
