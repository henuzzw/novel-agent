package com.novelagent.planning.infrastructure;

import com.novelagent.planning.domain.OutlineVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutlineVersionRepository extends JpaRepository<OutlineVersion, UUID> {
    Optional<OutlineVersion> findFirstByProjectIdOrderByGenerationNumberDesc(UUID projectId);
    List<OutlineVersion> findAllByProjectIdOrderByGenerationNumberDesc(UUID projectId);
    Optional<OutlineVersion> findByIdAndProjectId(UUID id, UUID projectId);
}
