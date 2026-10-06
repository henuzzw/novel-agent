package com.novelagent.planning.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Codex 会话存储。
 *
 * <p>按项目与工作流保存供应商线程关联。线程 ID 是模型会话状态，不是小说规划版本；是否复用由调用策略决定。</p>
 */
public interface CodexAgentSessionRepository extends JpaRepository<CodexAgentSession, UUID> {

    /**
     * 按项目及工作流定位 Codex 会话关联，线程复用资格仍需模型客户端确认。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param workflowType 模型工作流阶段，用于隔离阶段状态与调用记录。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<CodexAgentSession> findByProjectIdAndWorkflowType(UUID projectId, String workflowType);
}
