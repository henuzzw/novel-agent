package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChapterContractVersionRepository extends JpaRepository<ChapterContractVersion, UUID> {
    Optional<ChapterContractVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
    Optional<ChapterContractVersion> findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
            UUID projectId, int chapterNumber, ChapterContractStatus status);
    Optional<ChapterContractVersion> findByIdAndProjectId(UUID id, UUID projectId);
}
