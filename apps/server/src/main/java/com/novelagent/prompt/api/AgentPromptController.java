package com.novelagent.prompt.api;

import com.novelagent.prompt.application.AgentPromptService;
import com.novelagent.prompt.domain.PromptRevision;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 全局提示词管理入口，仅交付当前用户的配置与历史，不接受任意用户或项目 ID。 */
@RestController
@RequestMapping("/api/v1/settings/prompts")
public class AgentPromptController {
    private final AgentPromptService service;

    public AgentPromptController(AgentPromptService service) {
        this.service = service;
    }

    /**
     * 列出当前用户可编辑的活动阶段模板及有效配置，不包含项目手稿或模型调用记录。
     */
    @GetMapping
    public List<AgentPromptService.View> list() {
        return service.list();
    }

    /**
     * 读取指定阶段的当前配置、默认文本及受保护业务规则。
     *
     * @param key 活动提示词模板键，由服务端目录校验
     */
    @GetMapping("/{key}")
    public AgentPromptService.View get(@PathVariable String key) {
        return service.get(key);
    }

    /**
     * 按预期配置版本保存阶段指令、会话角色和执行规则，仅影响后续模型请求。
     *
     * @param key 活动提示词模板键，由服务端目录校验
     * @param request 阶段指令、会话角色、执行规则及预期配置版本
     */
    @PutMapping("/{key}")
    public AgentPromptService.View save(
            @PathVariable String key, @Valid @RequestBody Edit request) {
        return service.save(
                key,
                request.systemPrompt(),
                request.sessionSystemPrompt(),
                request.guidance(),
                request.version());
    }

    /**
     * 按预期版本恢复默认配置并追加历史修订，不回滚历史生成结果。
     *
     * @param key 活动提示词模板键，由服务端目录校验
     * @param request 恢复默认操作的预期配置版本
     */
    @PostMapping("/{key}/reset")
    public AgentPromptService.View reset(
            @PathVariable String key, @Valid @RequestBody Version request) {
        return service.reset(key, request.version());
    }

    /**
     * 读取当前用户指定阶段的提示词修订历史，不自动载入或应用旧版本。
     *
     * @param key 活动提示词模板键，由服务端目录校验
     */
    @GetMapping("/{key}/history")
    public List<PromptRevision> history(@PathVariable String key) {
        return service.history(key);
    }

    public record Edit(
            @NotBlank @Size(max = 40000) String systemPrompt,
            @NotBlank @Size(max = 40000) String sessionSystemPrompt,
            @NotNull @Size(max = 40000) String guidance,
            @NotNull @Min(0) Long version) {}

    public record Version(@NotNull @Min(0) Long version) {}
}
