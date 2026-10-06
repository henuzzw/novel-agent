package com.novelagent.memory.api;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.memory.application.MemoryPreviewService;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.planning.application.ModelProvider;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 写作记忆预览。
 *
 * <p>展示指定章节与阶段的召回上下文和预算信息。预览不是一次小说生成，也不将召回内容重新写入正史。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/memory")
public class MemoryController {
    private final MemoryPreviewService service;

    public MemoryController(MemoryPreviewService service) { this.service = service; }

    /**
     * 按章节、阶段、供应商和查询语句展示预算内召回内容，不生成合同或正文。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param query 记忆检索语句或关键词，不作为新事实写入。
     * @param stage 本次记忆或生成所处阶段，用于选择工具与预算。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     */
    @GetMapping("/preview")
    public NovelMemoryContext preview(@PathVariable UUID projectId, @RequestParam int chapterNumber,
            @RequestParam String query,
            @RequestParam(defaultValue = "MANUSCRIPT") AgentStage stage,
            @RequestParam(defaultValue = "LOCAL_CODEX") ModelProvider provider) {
        return service.preview(projectId, chapterNumber, query, stage, provider);
    }
}
