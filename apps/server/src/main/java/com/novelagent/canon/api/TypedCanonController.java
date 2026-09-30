package com.novelagent.canon.api;

import com.novelagent.canon.application.TypedCanonQueryService;
import java.util.List;
import java.util.UUID;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/canon")
public class TypedCanonController {
    private final TypedCanonQueryService service;

    public TypedCanonController(TypedCanonQueryService service) {
        this.service = service;
    }

    @GetMapping("/entities")
    public List<StoryEntityResponse> entities(
            @PathVariable UUID projectId,
            @RequestParam(required = false) String type) {
        return service.entities(projectId, type);
    }

    @GetMapping("/timeline")
    public List<StoryEventResponse> timeline(@PathVariable UUID projectId) {
        return service.timeline(projectId);
    }

    @GetMapping("/entities/{entityId}/state")
    public List<EntityStateResponse> entityState(
            @PathVariable UUID projectId,
            @PathVariable UUID entityId) {
        return service.entityState(projectId, entityId);
    }

    @GetMapping("/foreshadows")
    public List<ForeshadowResponse> foreshadows(@PathVariable UUID projectId) {
        return service.foreshadows(projectId);
    }

    @GetMapping("/relationships")
    public List<StoryRelationshipResponse> relationships(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID entityId) {
        return service.relationships(projectId, entityId);
    }

    @GetMapping("/knowledge")
    public List<CharacterKnowledgeResponse> knowledge(
            @PathVariable UUID projectId,
            @RequestParam(required = false) UUID characterId) {
        return service.knowledge(projectId, characterId);
    }

    @GetMapping("/entities/{entityId}/aliases")
    public List<EntityAliasResponse> aliases(@PathVariable UUID projectId, @PathVariable UUID entityId) {
        return service.aliases(projectId, entityId);
    }

    @PostMapping("/entities/{entityId}/aliases")
    public EntityAliasResponse addAlias(@PathVariable UUID projectId, @PathVariable UUID entityId,
            @Valid @RequestBody CreateEntityAliasRequest request) {
        return service.addAlias(projectId, entityId, request.alias(), request.aliasType());
    }

    @GetMapping("/entities/{entityId}/mentions")
    public List<EntityMentionResponse> mentions(@PathVariable UUID projectId, @PathVariable UUID entityId) {
        return service.mentions(projectId, entityId);
    }
}
