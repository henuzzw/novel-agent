package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.domain.ManuscriptStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ManuscriptVersionRepository extends JpaRepository<ManuscriptVersion, UUID> {
    Optional<ManuscriptVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
    Optional<ManuscriptVersion> findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
            UUID projectId, int chapterNumber, ManuscriptStatus status);
    Optional<ManuscriptVersion> findByIdAndProjectId(UUID id, UUID projectId);
    Optional<ManuscriptVersion> findByIdAndProjectIdAndChapterNumber(UUID id, UUID projectId, int chapterNumber);
    List<ManuscriptVersion> findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
}
