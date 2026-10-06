package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 合同版本存储。
 *
 * <p>保存章节合同历史，分别按项目、章号及状态查找。最新草稿和最新 APPROVED 合同的查询用途不同；行版本用于并发编辑，不等于 versionNumber。</p>
 */
public interface ChapterContractVersionRepository extends JpaRepository<ChapterContractVersion, UUID> {
    /**
     * 返回本章最新保存版本，不筛选批准或接受状态；其可用性需服务层进一步判断。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ChapterContractVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);

    /**
     * 返回本章满足指定状态的最新版本，适用于确认合同或作者接受正文等精确来源选择。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param status 要查询或迁移到的业务状态，不能跳过状态门禁。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ChapterContractVersion> findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
            UUID projectId, int chapterNumber, ChapterContractStatus status);

    /**
     * 联合版本或记录 ID 与项目 ID 定位资源，避免单凭 UUID 跨项目读取；用户权限由服务层校验。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ChapterContractVersion> findByIdAndProjectId(UUID id, UUID projectId);

    /**
     * 同时限定版本 ID、项目和章号，防止用其他章节版本作为当前源。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ChapterContractVersion> findByIdAndProjectIdAndChapterNumber(UUID id, UUID projectId, int chapterNumber);

    /**
     * 按章节版本序号倒序列出历史记录，查询不会改变当前合同、正文或正史指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    List<ChapterContractVersion> findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
}
