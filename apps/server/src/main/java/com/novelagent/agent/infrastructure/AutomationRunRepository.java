package com.novelagent.agent.infrastructure;

import com.novelagent.agent.domain.AutomationRun;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AutomationRunRepository extends JpaRepository<AutomationRun, UUID> {
    List<AutomationRun> findTop50ByProjectIdOrderByCreatedAtDesc(UUID projectId);
    Optional<AutomationRun> findByProjectIdAndRequestKey(UUID projectId, UUID requestKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from AutomationRun r where r.id = :id and r.projectId = :projectId")
    Optional<AutomationRun> findLocked(UUID projectId, UUID id);
}
