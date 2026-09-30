package com.novelagent.memory.api;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.memory.application.ContextBudgetPlanner;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.memory.application.NovelMemoryService;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/memory")
public class MemoryController {
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actorProvider;
    private final NovelMemoryService memory;
    private final ContextBudgetPlanner budgetPlanner;

    public MemoryController(NovelProjectRepository projects, CurrentActorProvider actorProvider,
            NovelMemoryService memory, ContextBudgetPlanner budgetPlanner) {
        this.projects = projects;
        this.actorProvider = actorProvider;
        this.memory = memory;
        this.budgetPlanner = budgetPlanner;
    }

    @GetMapping("/preview")
    public NovelMemoryContext preview(@PathVariable UUID projectId, @RequestParam int chapterNumber,
            @RequestParam String query,
            @RequestParam(defaultValue = "MANUSCRIPT") AgentStage stage,
            @RequestParam(defaultValue = "LOCAL_CODEX") ModelProvider provider) {
        var project = projects.findById(projectId)
                .filter(value -> value.getOwnerId().equals(actorProvider.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        var budget = budgetPlanner.plan(stage, provider, query);
        return memory.recall(stage, projectId, chapterNumber, project.getCurrentCanonVersion(), query, budget);
    }
}
