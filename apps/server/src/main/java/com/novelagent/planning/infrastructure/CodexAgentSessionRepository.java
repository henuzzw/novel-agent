package com.novelagent.planning.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CodexAgentSessionRepository extends JpaRepository<CodexAgentSession, UUID> {

    Optional<CodexAgentSession> findByProjectIdAndWorkflowType(UUID projectId, String workflowType);
}
