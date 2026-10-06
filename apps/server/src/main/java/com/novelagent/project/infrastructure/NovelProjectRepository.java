package com.novelagent.project.infrastructure;

import com.novelagent.project.domain.NovelProject;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 小说项目存储。
 *
 * <p>保存项目及当前规划、正史指针，按所有者提供列表查询。通用 findById 本身不做用户鉴权，必须由访问服务限定归属。</p>
 */
public interface NovelProjectRepository extends JpaRepository<NovelProject, UUID> {

    /**
     * 按所有者返回其项目并按更新时间倒序排序，不返回其他用户项目。
     *
     * @param ownerId 项目或记录所属作者 ID。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    List<NovelProject> findAllByOwnerIdOrderByUpdatedAtDesc(UUID ownerId);
}
