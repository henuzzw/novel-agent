package com.novelagent.planning.infrastructure;

import com.novelagent.planning.domain.StoryDirectionSet;
import com.novelagent.planning.domain.StoryDirectionStatus;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 故事方向集合存储。
 *
 * <p>保存多方向候选集合，支持按状态查找最新集合及项目内定位。作者选择和集合行版本约束由业务服务及领域对象处理。</p>
 */
public interface StoryDirectionSetRepository extends JpaRepository<StoryDirectionSet, UUID> {

    /**
     * 返回项目最新生成版本；最新可能是草稿，不能因此替换项目当前发布指针。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<StoryDirectionSet> findFirstByProjectIdOrderByGenerationNumberDesc(UUID projectId);

    /**
     * 返回项目指定业务状态下的最新生成集合，不放宽状态门禁。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param status 要查询或迁移到的业务状态，不能跳过状态门禁。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<StoryDirectionSet> findFirstByProjectIdAndStatusOrderByGenerationNumberDesc(
            UUID projectId, StoryDirectionStatus status);

    /**
     * 联合版本或记录 ID 与项目 ID 定位资源，避免单凭 UUID 跨项目读取；用户权限由服务层校验。
     *
     * @param id 当前方法所操作记录的稳定 ID。
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<StoryDirectionSet> findByIdAndProjectId(UUID id, UUID projectId);
}
