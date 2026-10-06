package com.novelagent.memory.application;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.agent.tool.AgentToolOrchestrator;
import com.novelagent.agent.tool.NovelToolRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 长期记忆召回。
 *
 * <p>协调只读工具召回正史、章节摘要、向量相似内容及相邻章衔接。按上下文预算分配输入，未来计划仅供创作依据，不当成已经发生的事实。</p>
 */
@Service
public class NovelMemoryService {
    private final AgentToolOrchestrator tools;

    public NovelMemoryService(AgentToolOrchestrator tools) {
        this.tools = tools;
    }

    /**
     * 按阶段、项目、章节与预算召回只读记忆，分别标记有效事实、前文参考和未来计划。
     *
     * @param stage 本次记忆或生成所处阶段，用于选择工具与预算。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param canonVersion 有效正史版本水位，与记录行版本不同。
     * @param query 记忆检索语句或关键词，不作为新事实写入。
     * @param budget 本次上下文或检查预算，约束输入范围但不是实际费用。
     */
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
