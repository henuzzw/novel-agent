package com.novelagent.ingest.api;

import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.ingest.application.ImportedPlanningService;
import jakarta.validation.Valid;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 作品导入。
 *
 * <p>接收原文件、确认识别章节及启动反推规划，支持原文件下载。原文解析确认与小说规划生成是不同动作；改编和续写保留不同事实边界。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/imports")
public class WorkImportController {
    private final WorkImportService service;
    private final ImportedPlanningService planning;

    public WorkImportController(WorkImportService service, ImportedPlanningService planning) {
        this.service = service;
        this.planning = planning;
    }

    /**
     * 接收 multipart 原文件并返回导入识别报告；文件安全与格式限制由导入服务检查。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param file 上传的原始文件，仍须经过类型和大小检查。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<WorkImportResponse> upload(
            @PathVariable UUID projectId,
            @RequestPart("file") MultipartFile file) {
        WorkImportResponse result = service.upload(projectId, file);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + projectId + "/imports/" + result.id()))
                .body(result);
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<WorkImportResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     */
    @GetMapping("/{importId}")
    public WorkImportResponse get(@PathVariable UUID projectId, @PathVariable UUID importId) {
        return service.get(projectId, importId);
    }

    /**
     * 确认识别出的导入章节可供后续解析，保留原文件；此确认不自动生成故事圣经或发布规划。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     */
    @PostMapping("/{importId}/actions/confirm")
    public WorkImportResponse confirm(@PathVariable UUID projectId, @PathVariable UUID importId) {
        return service.confirm(projectId, importId);
    }

    /**
     * 依据已确认的原文解析报告执行圣经与大纲反推，返回两份草稿；确认导入文件本身不足以跳过解析确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/{importId}/actions/reverse-plan")
    public ReversePlanResponse reversePlan(@PathVariable UUID projectId, @PathVariable UUID importId,
            @Valid @RequestBody ReversePlanRequest request) {
        return planning.generate(projectId, importId, request);
    }

    /**
     * 读取原始或已确认来源资料并保留来源版本，供下载、证据引用或后续业务复核。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     */
    @GetMapping("/{importId}/source")
    public ResponseEntity<byte[]> source(@PathVariable UUID projectId, @PathVariable UUID importId) {
        WorkImportService.SourceFile source = service.source(projectId, importId);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(source.filename(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(source.mediaType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(source.content());
    }
}
