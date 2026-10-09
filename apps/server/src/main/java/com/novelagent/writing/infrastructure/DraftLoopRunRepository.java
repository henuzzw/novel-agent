package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.DraftLoopRun;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** 仅保存单章自动编辑进度；项目权限、锁和来源复核由 Store 负责。 */
public interface DraftLoopRunRepository extends JpaRepository<DraftLoopRun, UUID> {
    Optional<DraftLoopRun> findByIdAndProjectId(UUID id, UUID projectId);
    Optional<DraftLoopRun> findByProjectIdAndRequestKey(UUID projectId, UUID requestKey);
    List<DraftLoopRun> findTop20ByProjectIdAndChapterNumberOrderByCreatedAtDesc(UUID projectId, int chapter);
    List<DraftLoopRun> findByProjectIdAndStatusIn(UUID projectId, List<DraftLoopRun.Status> statuses);
    List<DraftLoopRun> findByWorkerIdentityAndStatusIn(String identity, List<DraftLoopRun.Status> statuses);
}
