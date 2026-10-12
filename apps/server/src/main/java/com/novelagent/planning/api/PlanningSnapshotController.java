package com.novelagent.planning.api;

import com.novelagent.planning.application.ConfirmedSnowflakeService;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Read-only projection of the confirmed Snowflake version; no separate generate/edit/publish workflow. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/planning-snapshot")
public class PlanningSnapshotController {
    private final ConfirmedSnowflakeService service;
    public PlanningSnapshotController(ConfirmedSnowflakeService service) { this.service = service; }

    @GetMapping
    public ResponseEntity<StoryBibleResponse> current(@PathVariable UUID projectId) {
        return service.current(projectId).map(value -> ResponseEntity.ok()
                .cacheControl(org.springframework.http.CacheControl.noStore()).body(value))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
