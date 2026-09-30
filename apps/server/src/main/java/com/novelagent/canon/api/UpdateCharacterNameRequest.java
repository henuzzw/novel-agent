package com.novelagent.canon.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateCharacterNameRequest(
        @NotBlank @Size(max = 200) String canonicalName,
        @Size(max = 200) String nickname,
        @Size(max = 200) String title) {
}
