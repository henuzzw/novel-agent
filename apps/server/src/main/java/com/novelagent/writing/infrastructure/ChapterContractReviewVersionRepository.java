package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.ChapterContractReviewVersion;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 合同审阅存储。
 *
 * <p>保存合同审阅版本及对应源合同 ID、行版本。最新报告是否适用必须由服务再检查来源，不能仅凭查询顺序批准合同。</p>
 */
public interface ChapterContractReviewVersionRepository extends JpaRepository<ChapterContractReviewVersion, UUID> {
    /**
     * 返回本章最新保存版本，不筛选批准或接受状态；其可用性需服务层进一步判断。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ChapterContractReviewVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(
            UUID projectId, int chapterNumber);
    /**
     * 联合版本或记录 ID 与项目 ID 定位资源，避免单凭 UUID 跨项目读取；用户权限由服务层校验。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ChapterContractReviewVersion> findByIdAndProjectId(UUID id, UUID projectId);
}
