package com.novelagent.planning.api;

import com.novelagent.planning.application.PlanningMaterialSyncService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/planning-materials")
public class PlanningMaterialController {
    private final PlanningMaterialSyncService service;
    public PlanningMaterialController(PlanningMaterialSyncService service) { this.service = service; }

    @PostMapping("/actions/sync")
    public ResponseEntity<Void> sync(@PathVariable UUID projectId) {
        service.syncCurrent(projectId);
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/characters")
    public ResponseEntity<List<PlanningMaterialSyncService.CharacterSnapshot>> characters(@PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.characters(projectId));
    }
    @GetMapping("/relationships")
    public ResponseEntity<List<PlanningMaterialSyncService.PlannedRelationship>> relationships(@PathVariable UUID projectId,
            @RequestParam(required = false) UUID characterId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.relationships(projectId, characterId));
    }
    @GetMapping("/plan-origins")
    public ResponseEntity<List<PlanningMaterialSyncService.PlanOrigin>> origins(@PathVariable UUID projectId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.origins(projectId));
    }
}
