package com.novelagent.agent.tool;

import com.novelagent.memory.application.NovelMemoryContext;
import java.util.List;

public record NovelToolResult(
        List<NovelMemoryContext.SemanticMemory> semanticMemories,
        List<NovelMemoryContext.GraphFact> graphFacts) {

    public static NovelToolResult semantic(List<NovelMemoryContext.SemanticMemory> memories) {
        return new NovelToolResult(List.copyOf(memories), List.of());
    }

    public static NovelToolResult facts(List<NovelMemoryContext.GraphFact> facts) {
        return new NovelToolResult(List.of(), List.copyOf(facts));
    }
}
