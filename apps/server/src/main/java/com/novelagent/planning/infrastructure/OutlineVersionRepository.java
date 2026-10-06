package com.novelagent.planning.infrastructure;

import com.novelagent.planning.domain.OutlineVersion;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 大纲版本存储。
 *
 * <p>保存完整分层大纲历史，generationNumber 与 rowVersion 分别表示生成序号和并发编辑版本。最新保存项不必已发布，项目当前指针由服务层选择。</p>
 */
public interface OutlineVersionRepository extends JpaRepository<OutlineVersion, UUID> {
    /**
     * 返回项目最新生成版本；最新可能是草稿，不能因此替换项目当前发布指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<OutlineVersion> findFirstByProjectIdOrderByGenerationNumberDesc(UUID projectId);
    /**
     * 按生成序号倒序返回项目全部历史版本，保留草稿与发布状态差别。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    List<OutlineVersion> findAllByProjectIdOrderByGenerationNumberDesc(UUID projectId);
    /**
     * 联合版本或记录 ID 与项目 ID 定位资源，避免单凭 UUID 跨项目读取；用户权限由服务层校验。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<OutlineVersion> findByIdAndProjectId(UUID id, UUID projectId);
}
