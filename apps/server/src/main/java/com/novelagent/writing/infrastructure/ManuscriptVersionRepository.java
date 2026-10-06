package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.domain.ManuscriptStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 正文版本存储。
 *
 * <p>保存单份正文的版本历史和来源关联，可分别查询最新草稿及指定状态正文。作者接受与 active 正史不是同一条件，正史关联另由提交表维护。</p>
 */
public interface ManuscriptVersionRepository extends JpaRepository<ManuscriptVersion, UUID> {
    /**
     * 返回本章最新保存版本，不筛选批准或接受状态；其可用性需服务层进一步判断。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ManuscriptVersion> findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
    /**
     * 返回本章满足指定状态的最新版本，适用于确认合同或作者接受正文等精确来源选择。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param status 要查询或迁移到的业务状态，不能跳过状态门禁。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ManuscriptVersion> findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
            UUID projectId, int chapterNumber, ManuscriptStatus status);
    /**
     * 联合版本或记录 ID 与项目 ID 定位资源，避免单凭 UUID 跨项目读取；用户权限由服务层校验。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ManuscriptVersion> findByIdAndProjectId(UUID id, UUID projectId);
    /**
     * 同时限定版本 ID、项目和章号，防止用其他章节版本作为当前源。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<ManuscriptVersion> findByIdAndProjectIdAndChapterNumber(UUID id, UUID projectId, int chapterNumber);
    /**
     * 按章节版本序号倒序列出历史记录，查询不会改变当前合同、正文或正史指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    List<ManuscriptVersion> findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(UUID projectId, int chapterNumber);
}
