package com.novelagent.canon.api;

import java.util.UUID;

public record EntityAliasResponse(UUID id, UUID entityId, String alias, String aliasType,
        long canonVersionFrom) {
}
