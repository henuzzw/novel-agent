package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.QualityReviewVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QualityReviewVersionRepository extends JpaRepository<QualityReviewVersion, UUID> {
    Optional<QualityReviewVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
    Optional<QualityReviewVersion> findByIdAndProjectIdAndChapterNumber(UUID id, UUID projectId, int chapterNumber);
}
