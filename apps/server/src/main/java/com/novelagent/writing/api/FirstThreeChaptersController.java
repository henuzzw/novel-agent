package com.novelagent.writing.api;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.application.FirstThreeChaptersService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 前三章连读检查。
 *
 * <p>查询完整三章的来源与预算，或由作者明确触发专项模型检查。缺章或超预算不能冒充已检查，报告不替代作者确认。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/opening-review")
public class FirstThreeChaptersController {
    private final FirstThreeChaptersService service;
    public FirstThreeChaptersController(FirstThreeChaptersService service) { this.service = service; }
    public record CheckRequest(List<UUID> manuscriptIds, ModelProvider provider, String instruction,
            String expectedFingerprint, int maxInputTokens) { }
    /**
     * 读取完整三章与依赖来源、历史报告及保守预算，未调用模型；依据不足或预算超限明确显示不可检查。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param manuscriptIds 本次选定的正文版本 ID 集合。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     * @param instruction 作者本次要求，只能在已有事实与授权边界内执行。
     */
    @GetMapping
    public FirstThreeChaptersService.View get(@PathVariable UUID projectId,
            @RequestParam(required = false) List<UUID> manuscriptIds,
            @RequestParam(defaultValue = "LOCAL_TEMPLATE") ModelProvider provider, @RequestParam(defaultValue = "") String instruction) {
        return service.get(projectId, manuscriptIds, provider, instruction);
    }
    /**
     * 核对作者提供的来源指纹和输入额度后，事务外通读完整三章一次，保存时重检来源；不裁剪后声称完整检查。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/check")
    public ResponseEntity<FirstThreeChaptersService.Report> check(@PathVariable UUID projectId, @RequestBody CheckRequest request) {
        return ResponseEntity.status(201).body(service.check(projectId, request.manuscriptIds(), request.provider(),
                request.instruction(), request.expectedFingerprint(), request.maxInputTokens()));
    }
}
