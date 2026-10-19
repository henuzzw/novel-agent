package com.novelagent.canon.infrastructure;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

/**
 * 为图投影的 MERGE/MATCH 键建立索引，避免按项目全量扫描。只建普通索引、不建唯一约束：
 * 已有数据可能含重复节点，约束会创建失败。启动后在后台执行，Neo4j 不可用时只记录告警，不阻塞启动。
 */
@Component
@ConditionalOnProperty(name = "app.neo4j.schema-init", havingValue = "true", matchIfMissing = true)
class Neo4jSchemaInitializer {
    private static final Logger log = LoggerFactory.getLogger(Neo4jSchemaInitializer.class);
    static final List<String> STATEMENTS = List.of(
            "CREATE INDEX story_entity_project_name IF NOT EXISTS FOR (n:StoryEntity) ON (n.projectId, n.name)",
            "CREATE INDEX story_entity_project_entity IF NOT EXISTS FOR (n:StoryEntity) ON (n.projectId, n.entityId)",
            "CREATE INDEX story_entity_project_version IF NOT EXISTS FOR (n:StoryEntity) ON (n.projectId, n.canonVersion)",
            "CREATE INDEX story_fact_project_fact IF NOT EXISTS FOR (n:StoryFact) ON (n.projectId, n.factId)",
            "CREATE INDEX story_fact_project_stable IF NOT EXISTS FOR (n:StoryFact) ON (n.projectId, n.stableFactId)",
            "CREATE INDEX story_fact_project_version IF NOT EXISTS FOR (n:StoryFact) ON (n.projectId, n.canonVersion)",
            "CREATE INDEX story_event_project_event IF NOT EXISTS FOR (n:StoryEvent) ON (n.projectId, n.eventId)",
            "CREATE INDEX story_event_project_version IF NOT EXISTS FOR (n:StoryEvent) ON (n.projectId, n.canonVersion)",
            "CREATE INDEX state_change_project_change IF NOT EXISTS FOR (n:StateChange) ON (n.projectId, n.changeId)",
            "CREATE INDEX state_change_project_version IF NOT EXISTS FOR (n:StateChange) ON (n.projectId, n.canonVersion)",
            "CREATE INDEX foreshadow_project_item IF NOT EXISTS FOR (n:Foreshadow) ON (n.projectId, n.foreshadowId)",
            "CREATE INDEX foreshadow_project_version IF NOT EXISTS FOR (n:Foreshadow) ON (n.projectId, n.canonVersion)",
            "CREATE INDEX novel_project_project IF NOT EXISTS FOR (n:NovelProject) ON (n.projectId)");

    private final Neo4jClient neo4j;

    Neo4jSchemaInitializer(Neo4jClient neo4j) {
        this.neo4j = neo4j;
    }

    @EventListener(ApplicationReadyEvent.class)
    void initializeInBackground() {
        Thread.ofVirtual().name("neo4j-schema-init").start(this::initialize);
    }

    int initialize() {
        int created = 0;
        for (String statement : STATEMENTS) {
            try {
                neo4j.query(statement).run();
                created++;
            } catch (RuntimeException failure) {
                log.warn("Neo4j index initialization skipped: {} ({})", statement, failure.getMessage());
                if (created == 0) return 0; // 首条即失败通常是连接不可用，不再逐条等待超时。
            }
        }
        return created;
    }
}
