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
    public AgentPromptController(AgentPromptService service) { this.service = service; }

    @GetMapping public List<AgentPromptService.View> list() { return service.list(); }
    @GetMapping("/{key}") public AgentPromptService.View get(@PathVariable String key) { return service.get(key); }
    @PutMapping("/{key}") public AgentPromptService.View save(@PathVariable String key, @Valid @RequestBody Edit request) {
        return service.save(key, request.systemPrompt(), request.guidance(), request.version());
    }
    @PostMapping("/{key}/reset") public AgentPromptService.View reset(@PathVariable String key, @Valid @RequestBody Version request) {
        return service.reset(key, request.version());
    }
    @GetMapping("/{key}/history") public List<PromptRevision> history(@PathVariable String key) { return service.history(key); }

    public record Edit(@NotBlank @Size(max = 40000) String systemPrompt,
            @NotNull @Size(max = 40000) String guidance, @NotNull @Min(0) Long version) { }
    public record Version(@NotNull @Min(0) Long version) { }
}
