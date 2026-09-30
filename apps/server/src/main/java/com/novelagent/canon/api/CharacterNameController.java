package com.novelagent.canon.api;

import com.novelagent.canon.application.CharacterNameService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/characters")
public class CharacterNameController {
    private final CharacterNameService service;

    public CharacterNameController(CharacterNameService service) { this.service = service; }

    @GetMapping
    public List<CharacterNameResponse> list(@PathVariable UUID projectId) { return service.list(projectId); }

    @PostMapping("/actions/initialize-from-bible")
    public List<CharacterNameResponse> initialize(@PathVariable UUID projectId) {
        return service.initializeFromStoryBible(projectId);
    }

    @PutMapping("/{characterId}")
    public ResponseEntity<CharacterNameResponse> update(@PathVariable UUID projectId,
            @PathVariable UUID characterId, @RequestHeader("If-Match") String match,
            @Valid @RequestBody UpdateCharacterNameRequest request) {
        CharacterNameResponse value = service.update(projectId, characterId,
                Long.parseLong(match.replace("\"", "").trim()), request.canonicalName(),
                request.nickname(), request.title());
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }
}
