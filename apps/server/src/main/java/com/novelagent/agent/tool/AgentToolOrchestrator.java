package com.novelagent.agent.tool;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.MemoryBudgetPlan;
import com.novelagent.memory.application.NovelMemoryContext;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AgentToolOrchestrator {
    private static final Logger log = LoggerFactory.getLogger(AgentToolOrchestrator.class);

    private final AgentToolPolicy policy;
    private final Map<NovelToolName, NovelReadTool> tools;
    private final MemoryBudgetAllocator allocator;

    public AgentToolOrchestrator(AgentToolPolicy policy, List<NovelReadTool> tools,
            MemoryBudgetAllocator allocator) {
        this.policy = policy;
        this.tools = index(tools);
        this.allocator = allocator;
    }

    public NovelMemoryContext gather(AgentStage stage, NovelToolRequest request, MemoryBudgetPlan budget) {
        List<NovelMemoryContext.SemanticMemory> semantic = new ArrayList<>();
        List<NovelMemoryContext.GraphFact> facts = new ArrayList<>();
        List<String> usedTools = new ArrayList<>();

        for (NovelToolName toolName : policy.toolsFor(stage)) {
            NovelReadTool tool = require(toolName);
            try {
                NovelToolResult result = tool.execute(request);
                semantic.addAll(result.semanticMemories());
                facts.addAll(result.graphFacts());
                usedTools.add(toolName.name());
            } catch (RuntimeException exception) {
                if (!tool.optional()) {
                    throw exception;
                }
                log.warn("Optional novel tool {} unavailable for project {}; continuing without it",
                        toolName, request.projectId(), exception);
            }
        }

        return allocator.allocate(deduplicateSemantic(semantic), List.copyOf(facts), budget, usedTools);
    }

    private NovelReadTool require(NovelToolName name) {
        NovelReadTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalStateException("未注册小说只读工具：" + name);
        }
        return tool;
    }

    private static Map<NovelToolName, NovelReadTool> index(List<NovelReadTool> tools) {
        Map<NovelToolName, NovelReadTool> result = new EnumMap<>(NovelToolName.class);
        for (NovelReadTool tool : tools) {
            if (result.put(tool.name(), tool) != null) {
                throw new IllegalStateException("重复注册小说只读工具：" + tool.name());
            }
        }
        return Map.copyOf(result);
    }

    private static List<NovelMemoryContext.SemanticMemory> deduplicateSemantic(
            List<NovelMemoryContext.SemanticMemory> candidates) {
        Map<String, NovelMemoryContext.SemanticMemory> unique = new LinkedHashMap<>();
        for (NovelMemoryContext.SemanticMemory candidate : candidates) {
            String key = candidate.chapterNumber() + ":" + candidate.canonVersion();
            unique.merge(key, candidate, AgentToolOrchestrator::mergeMemory);
        }
        return List.copyOf(unique.values());
    }

    private static NovelMemoryContext.SemanticMemory mergeMemory(
            NovelMemoryContext.SemanticMemory first,
            NovelMemoryContext.SemanticMemory second) {
        String content = first.content() == null || first.content().isBlank() ? second.content() : first.content();
        double similarity = Math.min(first.similarity(), second.similarity());
        if (first.similarity() == 1.0) {
            similarity = second.similarity();
        }
        return new NovelMemoryContext.SemanticMemory(first.chapterNumber(), first.canonVersion(), similarity,
                first.summary(), content);
    }
}
