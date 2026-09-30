package com.novelagent.planning.infrastructure;

import com.novelagent.planning.domain.StoryBibleVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryBibleVersionRepository extends JpaRepository<StoryBibleVersion, UUID> {
    Optional<StoryBibleVersion> findFirstByProjectIdOrderByGenerationNumberDesc(UUID projectId);
    Optional<StoryBibleVersion> findByIdAndProjectId(UUID id, UUID projectId);
}
