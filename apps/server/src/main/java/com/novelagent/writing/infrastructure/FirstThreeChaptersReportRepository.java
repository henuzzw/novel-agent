package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.FirstThreeChaptersReport;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 三章报告存储。
 *
 * <p>按项目、作者和来源指纹读取完整三章专项历史报告。旧来源报告可保留展示，但不能作为当前三章的已完成检查。</p>
 */
public interface FirstThreeChaptersReportRepository extends JpaRepository<FirstThreeChaptersReport, UUID> {
    /**
     * 读取项目内该作者最近一次三章检查报告；展示历史不代表当前依据仍有效。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param authorId 报告或操作所属作者 ID。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<FirstThreeChaptersReport> findFirstByProjectIdAndAuthorIdOrderByVersionNumberDesc(UUID projectId, UUID authorId);
    /**
     * 按项目、作者和精确来源指纹读取可匹配的三章报告，不以相同章号近似复用结果。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param authorId 报告或操作所属作者 ID。
     * @param fingerprint 报告对应的精确来源指纹。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    Optional<FirstThreeChaptersReport> findFirstByProjectIdAndAuthorIdAndFingerprintOrderByVersionNumberDesc(
            UUID projectId, UUID authorId, String fingerprint);
}
