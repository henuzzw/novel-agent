package com.novelagent.canon.api;

import com.novelagent.canon.application.CharacterProfileService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/character-profiles")
public class CharacterProfileController {
    private final CharacterProfileService service;

    public CharacterProfileController(CharacterProfileService service) {
        this.service = service;
    }

    @GetMapping
    public List<CharacterProfileResponse> list(@PathVariable UUID projectId) {
        return service.list(projectId);
    }

    @PutMapping("/{characterId}")
    public ResponseEntity<CharacterProfileResponse> update(@PathVariable UUID projectId,
            @PathVariable UUID characterId, @RequestHeader("If-Match") String match,
            @Valid @RequestBody UpdateCharacterProfileRequest request) {
        CharacterProfileResponse value = service.update(projectId, characterId,
                Long.parseLong(match.replace("\"", "").trim()), request);
        return ResponseEntity.ok().eTag(Long.toString(value.version())).body(value);
    }
}
