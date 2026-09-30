package com.novelagent.agent.tool;

public interface NovelReadTool {
    NovelToolName name();

    NovelToolResult execute(NovelToolRequest request);

    default boolean optional() {
        return false;
    }
}
