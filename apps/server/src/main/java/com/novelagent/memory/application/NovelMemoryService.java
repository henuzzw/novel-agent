package com.novelagent.memory.application;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.agent.tool.AgentToolOrchestrator;
import com.novelagent.agent.tool.NovelToolRequest;
import java.util.UUID;
import java.util.List;
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
     * 按阶段白名单、章节范围及正史水位召回历史资料，再按预算去重裁剪；规划版本与人物关键词用于限定相关资料。
     *
     * @param stage 召回阶段，决定允许使用的工具及预算
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapterNumber 目标章节号，从 1 开始
     * @param canonVersion 本次可见的正史版本水位
     * @param query 章节计划及作者要求组成的检索文字
     * @param budget 召回内容的上下文预算
     */
    public NovelMemoryContext recall(
            AgentStage stage,
            UUID projectId,
            int chapterNumber,
            long canonVersion,
            String query,
            MemoryBudgetPlan budget) {
        return recall(
                stage, projectId, chapterNumber, canonVersion, query, budget, null, List.of());
    }

    /**
     * 按阶段白名单、章节范围及正史水位召回历史资料，再按预算去重裁剪；规划版本与人物关键词用于限定相关资料。
     *
     * @param stage 召回阶段，决定允许使用的工具及预算
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapterNumber 目标章节号，从 1 开始
     * @param canonVersion 本次可见的正史版本水位
     * @param query 章节计划及作者要求组成的检索文字
     * @param budget 召回内容的上下文预算
     * @param planningVersionId 检索所关联的确切规划资料版本
     * @param keywords 用于资料匹配的人物等关键词
     */
    public NovelMemoryContext recall(
            AgentStage stage,
            UUID projectId,
            int chapterNumber,
            long canonVersion,
            String query,
            MemoryBudgetPlan budget,
            UUID planningVersionId,
            List<String> keywords) {
        NovelToolRequest request =
                new NovelToolRequest(
                        projectId,
                        chapterNumber,
                        canonVersion,
                        query,
                        Math.min(30, budget.maxSemanticMemories() * 2),
                        Math.min(40, budget.maxGraphFacts() * 2),
                        planningVersionId,
                        keywords);
        return tools.gather(stage, request, budget);
    }
}
