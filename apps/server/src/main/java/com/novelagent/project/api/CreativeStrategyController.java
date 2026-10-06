package com.novelagent.project.api;

import com.novelagent.project.application.CreativeStrategyService;
import com.novelagent.project.domain.CreativeStrategy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/settings/creative-strategy")
public class CreativeStrategyController {
    private final CreativeStrategyService service;

    public CreativeStrategyController(CreativeStrategyService service) {
        this.service = service;
    }

    public record UpdateRequest(@NotNull CreativeStrategy strategy, @NotNull @PositiveOrZero Long version) {}

    @GetMapping
    public CreativeStrategyService.State get(@PathVariable UUID projectId) {
        return service.get(projectId);
    }

    @PutMapping
    public CreativeStrategyService.State update(@PathVariable UUID projectId, @Valid @RequestBody UpdateRequest request) {
        return service.update(projectId, request.strategy(), request.version());
    }
}
