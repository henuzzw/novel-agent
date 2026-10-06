package com.novelagent.planning.application;

import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 分块规划执行。
 *
 * <p>认领下一块后委托片段执行器生成，再保存批次进度或失败。每次显式请求推进一块，不启动整份大纲的无限后台付费循环。</p>
 */
@Service
public class PlanningBatchRunner {
    private final PlanningBatchService batches;
    private final PlanningCheckpointRunner checkpoints;

    public PlanningBatchRunner(PlanningBatchService batches, PlanningCheckpointRunner checkpoints) {
        this.batches = batches;
        this.checkpoints = checkpoints;
    }

    /**
     * 显式执行规划批次的下一块，并记录成功或失败；本次调用不自动生成剩余全部块。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     */
    public PlanningBatchService.View runNext(UUID projectId, UUID id, long version) {
        var claim = batches.claimNext(projectId, id, version);
        try {
            checkpoints.run(projectId, claim.checkpoint().id(), claim.checkpoint().version(),
                    claim.wordBudget(), claim.chapterCount());
            return batches.finish(projectId, id, claim.batchVersion());
        } catch (RuntimeException failure) {
            try {
                batches.fail(projectId, id, claim.batchVersion());
            } catch (RuntimeException stale) {
                failure.addSuppressed(stale);
            }
            throw failure;
        }
    }
}
