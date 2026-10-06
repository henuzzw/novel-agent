package com.novelagent.memory.application;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.ProjectAccessService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 写作记忆预览。
 *
 * <p>校验项目及章节依据后，根据阶段与供应商预算构造可查看的记忆包。前章正文、未来计划与有效正史使用不同标记，不混用知识边界。</p>
 */
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

    /**
     * 校验项目和章节依据，计算阶段预算并召回可展示记忆；缺失来源明确保留，不宣称完整通读。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param query 记忆检索语句或关键词，不作为新事实写入。
     * @param stage 本次记忆或生成所处阶段，用于选择工具与预算。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     */
    public NovelMemoryContext preview(UUID projectId, int chapterNumber, String query, AgentStage stage, ModelProvider provider) {
        var project = access.requireOwnedProject(projectId);
        var budget = budgetPlanner.plan(stage, provider, query);
        return memory.recall(stage, projectId, chapterNumber, project.getCurrentCanonVersion(), query, budget);
    }
}
