package com.novelagent.memory.application;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.ProjectAccessService;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MemoryPreviewService {
    private final ProjectAccessService access;
    private final NovelMemoryService memory;
    private final ContextBudgetPlanner budgetPlanner;

    public MemoryPreviewService(ProjectAccessService access, NovelMemoryService memory, ContextBudgetPlanner budgetPlanner) {
        this.access = access;
        this.memory = memory;
        this.budgetPlanner = budgetPlanner;
    }

    public NovelMemoryContext preview(UUID projectId, int chapterNumber, String query, AgentStage stage, ModelProvider provider) {
        var project = access.requireOwnedProject(projectId);
        var budget = budgetPlanner.plan(stage, provider, query);
        return memory.recall(stage, projectId, chapterNumber, project.getCurrentCanonVersion(), query, budget);
    }
}
