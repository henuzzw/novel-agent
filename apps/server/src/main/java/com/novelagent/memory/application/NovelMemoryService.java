package com.novelagent.memory.application;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.agent.tool.AgentToolOrchestrator;
import com.novelagent.agent.tool.NovelToolRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class NovelMemoryService {
    private final AgentToolOrchestrator tools;

    public NovelMemoryService(AgentToolOrchestrator tools) {
        this.tools = tools;
    }

    public NovelMemoryContext recall(AgentStage stage, UUID projectId, int chapterNumber,
            long canonVersion, String query, MemoryBudgetPlan budget) {
        NovelToolRequest request = new NovelToolRequest(
                projectId,
                chapterNumber,
                canonVersion,
                query,
                Math.min(30, budget.maxSemanticMemories() * 2),
                Math.min(40, budget.maxGraphFacts() * 2));
        return tools.gather(stage, request, budget);
    }
}
