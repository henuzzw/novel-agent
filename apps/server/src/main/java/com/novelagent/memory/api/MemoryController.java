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

@RestController
@RequestMapping("/api/v1/projects/{projectId}/memory")
public class MemoryController {
    private final MemoryPreviewService service;

    public MemoryController(MemoryPreviewService service) { this.service = service; }

    @GetMapping("/preview")
    public NovelMemoryContext preview(@PathVariable UUID projectId, @RequestParam int chapterNumber,
            @RequestParam String query,
            @RequestParam(defaultValue = "MANUSCRIPT") AgentStage stage,
            @RequestParam(defaultValue = "LOCAL_CODEX") ModelProvider provider) {
        return service.preview(projectId, chapterNumber, query, stage, provider);
    }
}
