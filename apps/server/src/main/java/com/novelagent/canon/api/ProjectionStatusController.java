package com.novelagent.canon.api;

import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/projection-status")
public class ProjectionStatusController {
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actor;
    private final JdbcTemplate jdbc;

    public ProjectionStatusController(NovelProjectRepository projects, CurrentActorProvider actor, JdbcTemplate jdbc) {
        this.projects = projects;
        this.actor = actor;
        this.jdbc = jdbc;
    }

    @GetMapping
    public ProjectionStatus status(@PathVariable UUID projectId) {
        NovelProject project = projects.findById(projectId)
                .filter(value -> value.getOwnerId().equals(actor.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        long canon = project.getCurrentCanonVersion();
        return new ProjectionStatus(
                canon,
                isPublished(projectId, canon),
                isProjected(projectId, canon, "PGVECTOR"),
                isProjected(projectId, canon, "NEO4J"));
    }

    private boolean isPublished(UUID projectId, long canonVersion) {
        String sql = """
                SELECT count(*)
                  FROM outbox_event o
                 WHERE o.payload->>'projectId' = ?
                   AND (o.payload->>'canonVersion')::bigint = ?
                   AND o.published_at IS NOT NULL
                """;
        Integer count = jdbc.queryForObject(sql, Integer.class, projectId.toString(), canonVersion);
        return count != null && count > 0;
    }

    private boolean isProjected(UUID projectId, long canonVersion, String projectionType) {
        String sql = """
                SELECT count(*)
                  FROM outbox_event o
                 WHERE o.payload->>'projectId' = ?
                   AND (o.payload->>'canonVersion')::bigint = ?
                   AND EXISTS (
                       SELECT 1
                         FROM projection_checkpoint p
                        WHERE p.event_id = o.id
                          AND p.projection_type = ?
                   )
                """;
        Integer count = jdbc.queryForObject(
                sql, Integer.class, projectId.toString(), canonVersion, projectionType);
        return count != null && count > 0;
    }

    public record ProjectionStatus(long canonVersion, boolean kafkaPublished, boolean pgvectorProjected,
            boolean neo4jProjected) {
    }
}
