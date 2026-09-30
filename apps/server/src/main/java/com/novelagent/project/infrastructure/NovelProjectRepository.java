package com.novelagent.project.infrastructure;

import com.novelagent.project.domain.NovelProject;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NovelProjectRepository extends JpaRepository<NovelProject, UUID> {

    List<NovelProject> findAllByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);
}

