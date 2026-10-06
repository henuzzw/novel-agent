package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.QualityReviewVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 质量报告存储。
 *
 * <p>保存四维质量报告与依据指纹，按项目及章号定位。查询最新报告仍需复核来源是否失效，报告不等于正文已批准。</p>
 */
public interface QualityReviewVersionRepository extends JpaRepository<QualityReviewVersion, UUID> {
    /**
     * 返回本章最新保存版本，不筛选批准或接受状态；其可用性需服务层进一步判断。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<QualityReviewVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
    /**
     * 同时限定版本 ID、项目和章号，防止用其他章节版本作为当前源。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<QualityReviewVersion> findByIdAndProjectIdAndChapterNumber(UUID id, UUID projectId, int chapterNumber);
}
