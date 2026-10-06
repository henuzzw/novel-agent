package com.novelagent.agent.api;

import com.novelagent.agent.domain.AutomationRun;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.agent.domain.AutomationStep;
import com.novelagent.planning.application.ModelProvider;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 自动任务的范围、执行阶段、等待原因与额度。
 *
 * <p>将领域对象转换为接口响应快照，明确保留自动任务的范围、执行阶段、等待原因与额度。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record AutomationRunResponse(UUID id, UUID projectId, UUID outlineId, int firstChapter,
        int lastChapter, int currentChapter, ModelProvider provider, AutomationStatus status,
        boolean cancelRequested, int attempt, String waitingReason, String errorCode,
        List<AutomationStep> steps, Instant createdAt, Instant updatedAt, boolean qualityReviewEnabled,
        int maxAutoRevisionRounds, int maxGenerationSteps, int usedGenerationSteps, int usedAutoRevisionRounds) {
    /**
     * 将领域版本映射为自动任务的范围、执行阶段、等待原因与额度。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param run 已读取或认领的自动任务快照。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static AutomationRunResponse from(AutomationRun run) {
        return new AutomationRunResponse(run.getId(), run.getProjectId(), run.getOutlineId(),
                run.getFirstChapter(), run.getLastChapter(), run.getCurrentChapter(), run.getProvider(),
                run.getStatus(), run.isCancelRequested(), run.getAttempt(), run.getWaitingReason(),
                run.getErrorCode(), run.getSteps(), run.getCreatedAt(), run.getUpdatedAt(), run.isQualityReviewEnabled(),
                run.getMaxAutoRevisionRounds(), run.getMaxGenerationSteps(), run.getUsedGenerationSteps(), run.getUsedAutoRevisionRounds());
    }
}
