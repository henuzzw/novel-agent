package com.novelagent.canon.infrastructure;

import com.novelagent.canon.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 正史事件存储。
 *
 * <p>保存与权威提交同事务产生的待发布事件，按创建顺序分批取出尚未发布项。数据库提交与 Kafka 发布不是同一个事务，消费者必须允许重放。</p>
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    /**
     * 取到期的未发布事件：跳过仍在退避期的事件，同一聚合若有更早的事件在退避中，后续事件也暂不发送，保持聚合内顺序。
     */
    String PUBLISHABLE_SQL = """
            SELECT o.* FROM outbox_event o
             WHERE o.published_at IS NULL
               AND (o.next_attempt_at IS NULL OR o.next_attempt_at <= now())
               AND NOT EXISTS (
                   SELECT 1 FROM outbox_event earlier
                    WHERE earlier.aggregate_id = o.aggregate_id
                      AND earlier.published_at IS NULL
                      AND earlier.next_attempt_at > now()
                      AND earlier.created_at < o.created_at)
             ORDER BY o.created_at
             LIMIT :limit
            """;

    @Query(value = PUBLISHABLE_SQL, nativeQuery = true)
    List<OutboxEvent> findPublishable(@Param("limit") int limit);
}
