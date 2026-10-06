package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.ChapterContractReviewVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChapterContractReviewVersionRepository extends JpaRepository<ChapterContractReviewVersion, UUID> {
    Optional<ChapterContractReviewVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(
            UUID projectId, int chapterNumber);
    Optional<ChapterContractReviewVersion> findByIdAndProjectId(UUID id, UUID projectId);
}
