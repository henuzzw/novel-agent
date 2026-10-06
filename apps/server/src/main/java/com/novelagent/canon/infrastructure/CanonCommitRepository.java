package com.novelagent.canon.infrastructure;

import com.novelagent.canon.domain.CanonCommit;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CanonCommitRepository extends JpaRepository<CanonCommit, UUID> {
    Optional<CanonCommit> findByReviewVersionId(UUID reviewVersionId);
    boolean existsByProjectIdAndChapterNumberAndActiveTrue(UUID projectId, int chapterNumber);
    Optional<CanonCommit> findByProjectIdAndChapterNumberAndActiveTrue(UUID projectId, int chapterNumber);
    boolean existsByProjectIdAndChapterNumberGreaterThanAndActiveTrue(UUID projectId, int chapterNumber);
    List<CanonCommit> findByProjectIdAndChapterNumberAndActiveFalse(UUID projectId, int chapterNumber);
}
