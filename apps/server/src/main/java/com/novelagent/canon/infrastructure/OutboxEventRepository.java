package com.novelagent.canon.infrastructure;

import com.novelagent.canon.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 正史事件存储。
 *
 * <p>保存与权威提交同事务产生的待发布事件，按创建顺序分批取出尚未发布项。数据库提交与 Kafka 发布不是同一个事务，消费者必须允许重放。</p>
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    /**
     * 按创建时间取最早 20 个尚未发布 Outbox 事件，供发布器分批处理；查询本身不标记已发布。
     *
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    List<OutboxEvent> findTop20ByPublishedAtIsNullOrderByCreatedAtAsc();
}
