package com.novelagent.planning.api;

import com.novelagent.planning.application.SnowflakePlanningService;
import com.novelagent.planning.domain.SnowflakePlan;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read candidate stage results; generation remains part of the existing Bible/import action. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/snowflake-plans")
public class SnowflakePlanningController {
    private final SnowflakePlanningService service;

    public SnowflakePlanningController(SnowflakePlanningService service) { this.service = service; }

    @GetMapping("/latest")
    public ResponseEntity<SnowflakePlan> latest(@PathVariable UUID projectId) {
        return service.latest(projectId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
