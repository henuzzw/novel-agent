package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.FirstThreeChaptersReport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FirstThreeChaptersReportRepository extends JpaRepository<FirstThreeChaptersReport, UUID> {
    Optional<FirstThreeChaptersReport> findFirstByProjectIdAndAuthorIdOrderByVersionNumberDesc(UUID projectId, UUID authorId);
    Optional<FirstThreeChaptersReport> findFirstByProjectIdAndAuthorIdAndFingerprintOrderByVersionNumberDesc(
            UUID projectId, UUID authorId, String fingerprint);
}
