package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.ChapterReviewVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChapterReviewVersionRepository extends JpaRepository<ChapterReviewVersion, UUID> {
    Optional<ChapterReviewVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
    Optional<ChapterReviewVersion> findByIdAndProjectId(UUID id, UUID projectId);
}
