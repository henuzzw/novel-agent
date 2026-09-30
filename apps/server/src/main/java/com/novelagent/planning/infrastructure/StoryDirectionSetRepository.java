package com.novelagent.planning.infrastructure;

import com.novelagent.planning.domain.StoryDirectionSet;
import com.novelagent.planning.domain.StoryDirectionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoryDirectionSetRepository extends JpaRepository<StoryDirectionSet, UUID> {

    Optional<StoryDirectionSet> findFirstByProjectIdOrderByGenerationNumberDesc(UUID projectId);

    Optional<StoryDirectionSet> findFirstByProjectIdAndStatusOrderByGenerationNumberDesc(
            UUID projectId, StoryDirectionStatus status);

    Optional<StoryDirectionSet> findByIdAndProjectId(UUID id, UUID projectId);
}
