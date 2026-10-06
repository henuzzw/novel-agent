package com.novelagent.canon.infrastructure;

import com.novelagent.canon.domain.CanonCommit;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 正史提交存储。
 *
 * <p>保存正史提交历史并区分 active 当前提交与已替换记录。审稿 ID 用于幂等定位；后续章已有有效正史时限制前章替换。</p>
 */
public interface CanonCommitRepository extends JpaRepository<CanonCommit, UUID> {
    /**
     * 按审稿来源查找提交历史，供幂等提交判断；是否仍 active 及所属章节需另行核对。
     *
     * @param reviewVersionId 作者确认的审稿版本 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<CanonCommit> findByReviewVersionId(UUID reviewVersionId);
    /**
     * 判断项目该章是否存在当前 active 正史提交，失效提交不计入。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    boolean existsByProjectIdAndChapterNumberAndActiveTrue(UUID projectId, int chapterNumber);
    /**
     * 读取项目该章当前有效正史提交，没有有效提交时返回空。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<CanonCommit> findByProjectIdAndChapterNumberAndActiveTrue(UUID projectId, int chapterNumber);
    /**
     * 检查指定章之后是否已有有效正史，用于阻止未处理后续依赖的前章替换。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    boolean existsByProjectIdAndChapterNumberGreaterThanAndActiveTrue(UUID projectId, int chapterNumber);
    /**
     * 查询指定章已被替换的提交历史，不能用于当前正史状态或写作事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    List<CanonCommit> findByProjectIdAndChapterNumberAndActiveFalse(UUID projectId, int chapterNumber);
}
