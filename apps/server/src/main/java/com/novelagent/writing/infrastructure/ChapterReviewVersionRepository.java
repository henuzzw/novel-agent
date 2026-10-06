package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.ChapterReviewVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 章节审稿存储。
 *
 * <p>保存审稿历史及候选事实决定，限定项目与章节查询。报告批准不触发正史物化，正史写入由提交服务单独执行。</p>
 */
public interface ChapterReviewVersionRepository extends JpaRepository<ChapterReviewVersion, UUID> {
    /**
     * 返回本章最新保存版本，不筛选批准或接受状态；其可用性需服务层进一步判断。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ChapterReviewVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
    /**
     * 联合版本或记录 ID 与项目 ID 定位资源，避免单凭 UUID 跨项目读取；用户权限由服务层校验。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ChapterReviewVersion> findByIdAndProjectId(UUID id, UUID projectId);
}
