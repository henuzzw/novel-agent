package com.novelagent.canon.api;

import java.util.UUID;

public record CharacterNameResponse(
        UUID id,
        String roleKey,
        String sourceName,
        String canonicalName,
        String nickname,
        String title,
        long version) {
}
