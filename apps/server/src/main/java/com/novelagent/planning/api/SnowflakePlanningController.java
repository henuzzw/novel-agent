package com.novelagent.planning.api;

import com.novelagent.planning.application.SnowflakePlanningService;
import com.novelagent.planning.application.ConfirmedSnowflakeService;
import com.novelagent.planning.domain.SnowflakePlan;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read candidate stage results; generation is part of the imported Snowflake action. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/snowflake-plans")
public class SnowflakePlanningController {
    private final SnowflakePlanningService service;
    private final ConfirmedSnowflakeService confirmed;

    public SnowflakePlanningController(SnowflakePlanningService service, ConfirmedSnowflakeService confirmed) {
        this.service = service;
        this.confirmed = confirmed;
    }

    /** Pin the exact reviewed run; confirmation synchronizes planned materials, never canon or model output. */
    @PostMapping("/{planId}/actions/confirm")
    public StoryBibleResponse confirm(@PathVariable UUID projectId, @PathVariable UUID planId) {
        return confirmed.confirm(projectId, planId);
    }

    @GetMapping("/latest")
    public ResponseEntity<SnowflakePlan> latest(@PathVariable UUID projectId) {
        return service.latest(projectId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }
}
