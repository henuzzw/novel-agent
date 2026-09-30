package com.novelagent.canon.application;

import java.util.List;
import java.util.UUID;

public record EntityCatalogContext(List<EntityCatalogEntry> entities) {
    public static EntityCatalogContext empty() {
        return new EntityCatalogContext(List.of());
    }

    public record EntityCatalogEntry(UUID id, String type, String name, List<String> aliases,
            long recentMentionCount, boolean chapterRelevant) {
    }
}
