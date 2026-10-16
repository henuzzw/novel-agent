package com.novelagent.chat.api;

import com.novelagent.chat.application.*;
import com.novelagent.chat.domain.ChatSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Project-owned history and explicit author decisions; no model tool exposes the apply endpoints. */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat")
public class ProjectChatController {
    private final ProjectChatService service;
    private final ProjectChatStore store;
    private final ChatMaterials materials;
    public ProjectChatController(ProjectChatService service, ProjectChatStore store, ChatMaterials materials) {
        this.service = service; this.store = store; this.materials = materials;
    }
    public record SendRequest(@NotNull UUID id, @NotBlank @Size(max = 8000) String question) { }
    @GetMapping("/turns") public List<ProjectChatStore.Turn> list(@PathVariable UUID projectId) { return store.list(projectId); }
    @PostMapping("/turns") @ResponseStatus(HttpStatus.ACCEPTED)
    public ProjectChatStore.Turn send(@PathVariable UUID projectId, @Valid @RequestBody SendRequest request) {
        return service.send(projectId, request.id(), request.question());
    }
    @PostMapping("/turns/{id}/stop") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable UUID projectId, @PathVariable UUID id) { service.cancel(projectId, id); }
    @GetMapping("/sources") public List<ChatSource.Summary> sources(@PathVariable UUID projectId) {
        return materials.catalog(projectId).stream().map(ChatSource::summary).toList();
    }
    public record SourcePage(String key, String title, int offset, int end, int characters, String text) { }
    @GetMapping("/source") public SourcePage read(@PathVariable UUID projectId, @RequestParam String key,
            @RequestParam(defaultValue="0") int offset) {
        var source = materials.require(projectId, key);
        if (offset < 0 || offset > source.text().length()) throw new IllegalArgumentException("资料偏移无效");
        int end = Math.min(source.text().length(), offset + 6000);
        return new SourcePage(key, source.title(), offset, end, source.text().length(), source.text().substring(offset, end));
    }
    @GetMapping("/changes") public List<ProjectChatStore.Change> changes(@PathVariable UUID projectId) { return store.changes(projectId); }
    @PostMapping("/changes/{id}/{action}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(@PathVariable UUID projectId, @PathVariable UUID id, @PathVariable String action) { store.decide(projectId, id, action); }
}
