package com.novelagent.canon.application;

import com.novelagent.writing.domain.ChapterContractContent;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EntityCatalogService {
    private final JdbcTemplate jdbc;

    public EntityCatalogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public EntityCatalogContext forReview(UUID projectId, ChapterContractContent contract) {
        Set<String> relevantNames = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(contract.pov()), contract.locations().stream())
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.toSet());
        List<EntityCatalogContext.EntityCatalogEntry> entries = jdbc.query("""
                SELECT e.id, e.entity_type, e.canonical_name,
                       array_remove(array_agg(DISTINCT a.alias), NULL) AS aliases,
                       count(DISTINCT m.id) AS mention_count
                FROM story_entity e
                LEFT JOIN entity_alias a ON a.entity_id = e.id AND a.canon_version_to IS NULL
                LEFT JOIN entity_mention m ON m.resolved_entity_id = e.id
                WHERE e.project_id = ? AND e.canon_version_to IS NULL
                GROUP BY e.id, e.entity_type, e.canonical_name
                """, (rs, row) -> {
            String name = rs.getString("canonical_name");
            List<String> aliases = strings(rs.getArray("aliases"));
            boolean relevant = relevantNames.contains(name)
                    || aliases.stream().anyMatch(relevantNames::contains);
            return new EntityCatalogContext.EntityCatalogEntry(
                    rs.getObject("id", UUID.class), rs.getString("entity_type"), name,
                    aliases, rs.getLong("mention_count"), relevant);
        }, projectId);
        entries.sort(java.util.Comparator
                .comparing(EntityCatalogContext.EntityCatalogEntry::chapterRelevant,
                        java.util.Comparator.reverseOrder())
                .thenComparing(EntityCatalogContext.EntityCatalogEntry::recentMentionCount,
                        java.util.Comparator.reverseOrder())
                .thenComparing(EntityCatalogContext.EntityCatalogEntry::name));
        return new EntityCatalogContext(List.copyOf(entries));
    }

    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        Object value = array.getArray();
        return value instanceof String[] strings ? Arrays.asList(strings) : List.of();
    }
}
