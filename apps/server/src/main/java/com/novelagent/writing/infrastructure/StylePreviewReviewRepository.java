package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.StylePreviewReview;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 试写报告存储。
 *
 * <p>保存私有开头样例及其检查报告和修订尝试。按 ID 和项目定位不代替当前用户鉴权，来源有效性及次数由业务存储服务控制。</p>
 */
public interface StylePreviewReviewRepository extends JpaRepository<StylePreviewReview, UUID> {
    /**
     * 联合版本或记录 ID 与项目 ID 定位资源，避免单凭 UUID 跨项目读取；用户权限由服务层校验。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<StylePreviewReview> findByIdAndProjectId(UUID id, UUID projectId);
}
