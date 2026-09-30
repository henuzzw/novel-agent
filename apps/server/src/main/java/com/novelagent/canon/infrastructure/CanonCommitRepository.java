package com.novelagent.canon.infrastructure;

import com.novelagent.canon.domain.CanonCommit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CanonCommitRepository extends JpaRepository<CanonCommit, UUID> {
    Optional<CanonCommit> findByReviewVersionId(UUID reviewVersionId);
}
