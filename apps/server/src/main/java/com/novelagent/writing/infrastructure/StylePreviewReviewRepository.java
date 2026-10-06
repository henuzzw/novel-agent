package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.StylePreviewReview;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StylePreviewReviewRepository extends JpaRepository<StylePreviewReview, UUID> {
    Optional<StylePreviewReview> findByIdAndProjectId(UUID id, UUID projectId);
}
