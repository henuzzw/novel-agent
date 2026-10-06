package com.novelagent.agent.infrastructure;

import com.novelagent.agent.domain.AutomationRun;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

/**
 * 自动创作任务存储。
 *
 * <p>保存章节范围自动任务并按项目、幂等键查找。findLocked 用悲观锁认领任务，需由调用方在有效事务中使用。</p>
 */
public interface AutomationRunRepository extends JpaRepository<AutomationRun, UUID> {
    /**
     * 按项目返回最近 50 个自动任务，按创建时间倒序；未完成任务的业务排他性由服务另行检查。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    List<AutomationRun> findTop50ByProjectIdOrderByCreatedAtDesc(UUID projectId);
    /**
     * 按项目及幂等请求键定位自动任务，查询命中后仍须由服务核对本次参数一致。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param requestKey 自动任务的幂等键，同键重复请求必须保持参数一致。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<AutomationRun> findByProjectIdAndRequestKey(UUID projectId, UUID requestKey);

    /**
     * 使用 PESSIMISTIC_WRITE 锁定任务行，供认领或状态转换；调用方必须处于有效事务且检查项目归属。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AutomationRun r where r.id = :id and r.projectId = :projectId")
    Optional<AutomationRun> findLocked(UUID projectId, UUID id);
}
